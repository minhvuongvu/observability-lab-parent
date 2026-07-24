# Runbook

Procedures. What to do, in what order, and how to know it worked.

> **What is here and what is not.**
>
> | Question | Document |
> | --- | --- |
> | "An alert fired — what do I open first?" | [Alerting.md §6](Alerting.md#6-incident-response) |
> | "Something is broken and I do not know what" | [Troubleshooting.md](Troubleshooting.md) |
> | "I need to perform an operation on this system" | **Here** |
> | "How do I bring it up in the first place?" | [Deployment.md](Deployment.md) |
>
> This document is organised by *procedure*, not by symptom. Every procedure states its
> preconditions, its steps, and the check that proves it worked. A procedure without a verification
> step is a procedure that gets performed twice.

---

## 1. The six things to check before anything else

Run these before diagnosing, and before *measuring*. Five of the six take under a second.

```bash
./scripts/infra.sh health          # 1. is every container converged?
./scripts/chaos.sh list            # 2. is a fault still injected from an earlier experiment?
./scripts/chaos.sh app status      # 3. is an in-process fault still toggled on?
./scripts/vault.sh status          # 4. is Vault sealed?
docker stats --no-stream           # 5. is anything at its memory or CPU ceiling?
curl -s 'http://localhost:9090/api/v1/query?query=up==0'   # 6. is any scrape target down?
```

Checks 2 and 3 exist because the most expensive mistake in this lab is diagnosing an artefact. A
400 ms latency toxic left on `postgres` produces a system that looks broken in exactly the way a
regression looks broken, and nothing in the metrics says "somebody did this on purpose".

Check 4 is here for the opposite reason: a sealed Vault produces a system that looks **healthy**.
Every service keeps serving from the secrets it already holds, so checks 1, 5 and 6 all pass while
the next restart of anything will fail. It is the one fault in this stack with no immediate symptom.

**`./scripts/chaos.sh reset` clears both levels, everywhere, always.** It is the first command of any
investigation whose cause is not already known.

---

## 2. Routine operations

| Task | Command |
| --- | --- |
| Start the stack | `./scripts/infra.sh up` |
| Stop, keep data | `./scripts/infra.sh down` |
| Stop, delete all data | `./scripts/infra.sh destroy` |
| Restart everything | `./scripts/infra.sh restart` |
| Container states | `./scripts/infra.sh health` |
| Where things live | `./scripts/infra.sh urls` |
| Follow one component | `./scripts/infra.sh logs kafka` |
| Shell into a container | `./scripts/infra.sh exec redis sh` |
| Rebuild the two services | `./scripts/infra.sh build` |
| Mint a token | `TOKEN=$(./scripts/token.sh alice)` — `manager` for ADMIN |
| Edge status | `./scripts/gateway.sh status` |
| Clear every injected fault | `./scripts/chaos.sh reset` |

`infra.sh` passes any unrecognised command through to `docker compose` with all five `-f` flags, so
`./scripts/infra.sh top`, `./scripts/infra.sh config` and the rest work without repeating the file list.

---

## 3. Service lifecycle

### 3.1 Restart one service

```bash
cd docker/compose
docker compose restart order-service
```

**Preconditions.** None. The service drains for up to 30 s (`server.shutdown: graceful`).

**What happens around it.** Kong's active health check marks the target unhealthy after 3 failures at
5 s and stops routing; it returns after 2 successes at 10 s. Consul deregisters on clean shutdown and
re-registers on boot. Kafka rebalances the consumer group. Undelivered outbox rows stay in PostgreSQL
and go out on the next relay poll.

**Verify.**

```bash
curl -fsS http://localhost:8081/actuator/health/readiness      # {"status":"UP"}
./scripts/gateway.sh health                                     # the target is HEALTHY again
curl -s http://localhost:8500/v1/health/service/order-service | grep -c passing
```

**Expect a gap.** One replica per service means a restart is an outage of that service for its
duration and Kong answers 503. That is the topology, not a fault
([Deployment.md §12](Deployment.md#12-what-this-deployment-is-not)).

### 3.2 Deploy a code change to one service

```bash
cd docker/compose
docker compose build order-service && docker compose up -d order-service
```

`up -d <service>` recreates only that container. Use `./scripts/infra.sh build` when both changed.

**Verify** as in 3.1, then confirm the running image is the one just built:

```bash
docker inspect --format '{{.Config.Image}} {{.Created}}' lab-order-service
```

### 3.3 Roll back a service

```bash
# Requires a previously tagged image: LAB_SERVICE_TAG=known-good ./scripts/infra.sh build
# Set LAB_SERVICE_TAG=known-good in docker/compose/.env, then:
./scripts/infra.sh up
```

**A Flyway migration cannot be rolled back.** There are no down-migrations. If the bad change included
one, the rollback is `./scripts/infra.sh destroy` and a fresh start.

### 3.4 Scale a service

```bash
cd docker/compose
docker compose up -d --scale order-service=2 order-service
```

**This fails as written**, and the failure is instructive: `container_name` is set, and Compose refuses
to create two containers with the same name. Scaling requires removing `container_name` and the
published `ports` block for that service — at which point it is reachable only inside `lab-net`, which
is where Kong, Consul and Prometheus address it anyway.

What then changes, and it is the useful part of the exercise:

| Component | Behaviour with two instances |
| --- | --- |
| Consul | Both register; `query-passing: true` returns only the healthy ones |
| Kong | Routes to `order-service.upstream`, which has one static target — the second instance gets no edge traffic until it is added as a target |
| Kafka | The group rebalances; partitions are split across instances. Each topic has **3 partitions** and each instance runs `concurrency: 3`, so a second instance leaves half the listener threads idle — partitions are the cap, not threads |
| Outbox relay | **Both poll the same table.** The relay locks rows, so this is safe, but throughput does not double |
| gRPC | The channel resolves through Consul and balances client-side, so this hop *does* spread |
| Prometheus | Static targets in `prometheus.yml`; the second instance is not scraped until added |

Two of those are the lesson: a scale-out is not complete until discovery, the balancer *and* the
scrape config know about it.

### 3.5 Take a service out of rotation without stopping it

```bash
docker exec lab-order-service curl -fsS -X POST \
  'http://127.0.0.1:8081/actuator/health/readiness'    # not writable — see below
```

There is no readiness toggle exposed. The available lever is the fault proxy:

```bash
./scripts/chaos.sh down inventory-http    # Kong's health check fails, Kong stops routing
./scripts/chaos.sh heal inventory-http    # back in rotation
```

Note the asymmetry this exposes: Kong reaches the Inventory Service **directly**, while the Order
Service reaches it **through Toxiproxy**. A toxic on `inventory-http` therefore breaks
service-to-service calls while the public API keeps answering. That is a real production shape and
worth having seen once.

---

## 4. Reading health correctly

| Probe | Question it answers | Composed of |
| --- | --- | --- |
| `/actuator/health/liveness` | Should this container be restarted? | `livenessState` — nothing else |
| `/actuator/health/readiness` | Should traffic be routed here? | `readinessState`, `db`, `redis` |
| Docker healthcheck | Compose ordering and Kong routing | Readiness |
| Consul check | Should discovery hand this instance out? | `/actuator/health` at the **advertised** address |
| Kong active check | Should this target receive requests? | `/actuator/health/readiness`, 2 up / 3 down |

Three consequences worth internalising:

1. **A failing database does not restart the service.** It makes it unready. That is deliberate:
   restarting a service because its database is slow removes the only component that can still serve
   cached reads.
2. **Consul checks the advertised address**, which for the Inventory Service is `toxiproxy`. If the
   *path* is broken the service is unreachable, whatever the process thinks — so the check is correct
   even though it is not checking the process directly.
3. **`management.health.consul.enabled: false`.** Otherwise Consul health-checking `/actuator/health`
   would couple each service's health to the component doing the checking, and a registry blip would
   mark every service down.

---

## 5. Per-component procedures

### PostgreSQL — Order Service and Keycloak

```bash
docker exec -it lab-postgres psql -U postgres -d orderdb

# connection load, and who holds what
SELECT count(*), state FROM pg_stat_activity WHERE datname='orderdb' GROUP BY state;

# the outbox — the fastest answer to "why is this order still PENDING".
# published_at IS NULL is the definition of pending; there is no status column.
SELECT count(*) FILTER (WHERE published_at IS NULL) AS pending,
       count(*) FILTER (WHERE published_at IS NOT NULL) AS delivered,
       max(attempts) AS worst_attempts
FROM outbox_events;

# and the ones that keep failing
SELECT event_id, topic, attempts, last_error FROM outbox_events
WHERE published_at IS NULL AND attempts > 0 ORDER BY attempts DESC LIMIT 10;
```

One engine hosts `orderdb` and `keycloakdb` with separate owning roles. Neither can read the other's
data; a laptop cannot afford an engine per consumer, and role separation preserves the property that
actually matters.

### Oracle — Inventory Service

```bash
docker exec -it lab-oracle sqlplus inventory_user/LocalDevInventory1@//localhost:1521/FREEPDB1

SELECT product_sku, available_quantity, reserved_quantity FROM stock_levels ORDER BY 1;

-- idempotency ledger: which events this service has already applied
SELECT count(*) FROM processed_events;
```

Oracle is the slowest container to become healthy and the largest memory claim (2560M). A first
initialisation genuinely takes minutes — `./scripts/infra.sh logs oracle` and wait for
`DATABASE IS READY TO USE`.

### Kafka

```bash
# topics, groups, lag — all from the broker container
docker exec lab-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
docker exec lab-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group order-inventory-updated-group

# read the dead-letter topic without consuming it as a group.
# --timeout-ms matters: without it this blocks forever on an empty topic and
# looks broken. With it, an empty DLQ ends in "Processed a total of 0 messages",
# which is the answer you wanted.
docker exec lab-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
  --topic dead-letter-topic --from-beginning --max-messages 10 --timeout-ms 5000

# how many messages a topic holds, without consuming anything
docker exec lab-kafka /opt/kafka/bin/kafka-run-class.sh org.apache.kafka.tools.GetOffsetShell \
  --bootstrap-server kafka:9092 --topic dead-letter-topic
```

> **The class moved in Kafka 4.x.** It is `org.apache.kafka.tools.GetOffsetShell`, not the
> `kafka.tools.GetOffsetShell` that most tutorials still show. The old name fails with
> `ClassNotFoundException` — and if stderr is redirected away, a piped count silently reports **0**,
> which reads exactly like an empty topic. Verified on `apache/kafka:4.2.1`.

Kafka UI at <http://localhost:8090> shows the same things in a browser.

| Topic | Partitions | Retention | Used |
| --- | --- | --- | --- |
| `order-created` | 3 | 7 days | Yes |
| `inventory-updated` | 3 | 7 days | Yes |
| `retry-topic` | 3 | 7 days | **No** — created but never referenced by any code or config. Retries are in-process (`FixedBackOff`, 3 attempts, 1 s) and go straight to the dead-letter topic. Verified empty on a running stack |
| `dead-letter-topic` | 3 | **30 days** | Yes, as a destination. Nothing consumes it automatically. Failures are kept far longer than successes: a dead letter is only useful if it is still there when somebody finally looks at the alert |

**Never point an automatic consumer at `dead-letter-topic`.** A poison message replayed on a loop is
how a dead-letter queue becomes an outage.

### Redis

```bash
docker exec -it lab-redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning

INFO stats            # keyspace_hits / keyspace_misses — the hit ratio that matters
KEYS 'orders::*'      # small lab; do not do this on anything real
FLUSHDB               # safe here: every cached value is derived from a database
```

Redis holds only derived state. Flushing it costs a burst of cache misses and nothing else, which
makes it the cheapest lever for reproducing a cold-cache latency profile.

### MinIO

Console at <http://localhost:9001>, with `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`. The services use
`MINIO_APP_USER`, scoped to the invoice bucket — never root.

An invoice is rebuilt on demand if the object is missing, so losing the bucket degrades to a slower
first read rather than a lost document.

### Consul

```bash
curl -s http://localhost:8500/v1/catalog/services
curl -s http://localhost:8500/v1/health/service/inventory-service | grep -o '"Status":"[a-z]*"'
consul kv get config/application/data      # or via the UI at :8500
```

To change shared configuration at runtime:

```bash
docker exec -i lab-consul consul kv put config/application/data - <<'YAML'
info:
  platform:
    config-source: "Consul KV"
YAML
```

Both services watch the key and re-bind without a restart. `/actuator/info` is the visible proof.

### Vault

```bash
./scripts/vault.sh status      # seal state and mounts
./scripts/vault.sh whoami      # which PostgreSQL user the Order Service is really using
./scripts/vault.sh leases      # outstanding dynamic credentials
./scripts/vault.sh audit 50    # every secret access, including refusals
```

**Vault boots sealed after every restart.** It is not dev mode. `./scripts/infra.sh up` unseals it
before starting anything that needs a secret, but a bare `docker compose up`, a Docker Desktop
restart or a machine reboot leaves it sealed with nothing to say so except the `VaultSealed` alert.

Reference: [`docs/Vault.md`](Vault.md).

#### Vault is sealed

*Alert: `VaultSealed` / `VaultDown`.*

Nothing breaks immediately, and that is the difficult part. Running services keep serving from the
credentials and connections they already hold — latency flat, error rate flat, health endpoints
green. The stack breaks at the next lease renewal or the next service restart, up to an hour later,
by which point the cause looks unrelated.

```bash
./scripts/vault.sh unseal
```

If that reports the keys file missing, this Vault cannot be opened: `.vault-keys.json` holds the only
copy of the unseal shares. The lab recovery is `./scripts/infra.sh destroy && ./scripts/infra.sh up`,
which also destroys the databases.

Confirm recovery with `./scripts/vault.sh whoami` — the Order Service should be connected as a
`v-approle-order-se-...` user.

#### Dynamic credential expired

*Alert: `VaultLeaseCountCollapsed`.*

The Order Service's PostgreSQL credential was revoked or expired without renewal. Existing pooled
connections still work; the next one Hikari opens fails.

```bash
./scripts/vault.sh leases            # empty confirms it
docker restart lab-order-service     # re-leases at startup
./scripts/vault.sh whoami            # new v-approle-... user
```

If leases keep collapsing, check that `spring.cloud.vault.config.lifecycle.enabled` is still `true` —
without renewal the credential dies at its 1h TTL every time.

#### Vault authentication failing

*Alert: `VaultPermissionDenied`.*

Sustained 403s mean something is asking for a path its policy does not grant.

```bash
./scripts/vault.sh audit 50    # the denied path is named in the log
./scripts/vault.sh deny        # confirms the boundary still behaves as designed
```

Most common cause after a config change: a new `SPRING_PROFILES_ACTIVE` value. Spring Cloud Vault
also reads `secret/data/<service>/<profile>`, and Vault answers an ungranted path with 403 rather
than 404 — so a missing grant and a missing secret look identical. The policies grant
`secret/data/<service>/*` for exactly this reason.

#### Vault audit device failing

*Alert: `VaultAuditDeviceFailing`.*

**Vault refuses every request when all audit devices are failing.** This is a total outage of the
secret store, and the usual cause is a full disk rather than anything about Vault.

```bash
docker exec lab-vault df -h /vault/logs
docker system df                          # reclaim: docker system prune
```

### Keycloak

Admin console at <http://localhost:8080>, `KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD`.

The realm is imported from `infrastructure/keycloak/realm/observability-realm.json` on boot and
**skipped once the realm exists**. Changing the realm file therefore requires dropping
`lab-postgres-data`, which also drops `orderdb`.

The realm pins its RSA signing key (`rsa-lab-fixed`) so the public key Kong holds keeps verifying
across rebuilds. Rotating that key means editing `kong.yml` in the same commit.

### Kong and Nginx

```bash
./scripts/gateway.sh validate    # parse kong.yml without applying it
./scripts/gateway.sh reload      # apply, and wait until it is actually serving
./scripts/gateway.sh routes      # what is routed right now
./scripts/gateway.sh health      # upstream target health as Kong sees it
./scripts/gateway.sh plugins     # which plugins are active, and where
./scripts/gateway.sh status      # all of the above
```

Kong runs DB-less: `kong.yml` is the entire state of the gateway, so nothing can drift through someone
poking the admin API. **Always `validate` before `reload`** — a typo becomes an error message instead
of a gateway that refuses every request.

`reload` waits for `configuration_hash` to change before returning. Without that wait, a test run
immediately afterwards silently exercises the old configuration and concludes the change did not work.

### The observability stack

| Component | Reload without restart | UI |
| --- | --- | --- |
| Prometheus | `curl -X POST http://localhost:9090/-/reload` | :9090 |
| Alertmanager | `curl -X POST http://localhost:9093/-/reload` | :9093 |
| Grafana | Provisioning directories are re-read on an interval | :3000 |
| Loki | Restart | via Grafana |
| Tempo / Jaeger / Zipkin | Restart | :3200 / :16686 / :9411 |
| Pyroscope | Restart | :4040 |
| OTel Collector | Restart | zPages :55679 |

Alerts land in Mailpit (<http://localhost:8025>) and the webhook sink (<http://localhost:8099>), so an
alert can be *seen* arriving rather than assumed.

---

## 6. Incident procedures

Each of these starts from something a person would actually say.

### 6.1 "Orders are stuck PENDING"

`PENDING` means accepted but not settled. The settlement path is: outbox → `order-created` → Inventory
reserves in Oracle → `inventory-updated` → Order Service settles. Five places to break, checked in the
order that costs least.

```bash
# 1. Is the outbox draining?  (pending = published_at IS NULL)
docker exec -i lab-postgres psql -U postgres -d orderdb \
  -c "SELECT count(*) FILTER (WHERE published_at IS NULL) AS pending, count(*) AS total FROM outbox_events;"

# 2. Did the event reach Kafka?
docker exec lab-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group inventory-order-created-group

# 3. Is the Inventory Service consuming, or failing?
./scripts/infra.sh logs inventory-service | grep -i 'order-created\|dead'

# 4. Did it dead-letter instead?
docker exec lab-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
  --topic dead-letter-topic --from-beginning --max-messages 5

# 5. Is the reply leg consumed?
docker exec lab-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group order-inventory-updated-group
```

**The alert for this is `OrdersAcceptedButNotSettled` (critical).** If the outbox is growing,
`OutboxBacklogGrowing` fires first at >50 pending for 3 minutes — that pair, in that order, is the
signature of a broker problem rather than a consumer problem.

**Do not "fix" this by updating order rows directly.** The settlement is what decrements stock; an
order forced to `CONFIRMED` by hand is stock that was never reserved.

### 6.2 "The dead-letter topic is not empty"

`DeadLetterMessages` fires on any increase over 10 minutes. It is a warning, not a page, because the
messages are safe where they are.

1. Read them. Spring's `DeadLetterPublishingRecoverer` adds headers naming the source topic, partition,
   offset and the exception.
2. Decide whether the cause is a defect or a business refusal. Business refusals
   (`BusinessException`, `ValidationException`) are configured as **not retryable** and go to the
   dead-letter topic on the first failure — that is correct, and retrying "there is not enough stock"
   three times only wastes the budget.
3. Replaying is a manual act: produce the record back onto its source topic once the cause is fixed.
   There is no automatic replay, deliberately.

The Order Service's dead-letter path was, until step 17, unable to publish at all — the recoverer used
a template whose serializer could not handle the record, so the DLQ publication itself threw, the
record was never dead-lettered, the consumer blocked its partition, and lag sat at 1 with nothing to
explain it. That is why a dead-letter path that has never been exercised is a dead-letter path that
does not work.

```bash
./scripts/scenario.sh dead-letter     # exercises it end to end, and heals afterwards
```

### 6.3 "Latency is up and I do not know why"

```bash
./scripts/chaos.sh list && ./scripts/chaos.sh app status   # first. always.
```

Then follow the disagreement:

| What you see | What it means |
| --- | --- |
| `hikaricp_connections_pending > 0` | Threads are queueing for a connection. The pool is 10; this is the first thing that runs out |
| `pg_up == 1` **and** the service reporting timeouts | The fault is in the **path**, not the datastore. Exporters bypass Toxiproxy on purpose, so this disagreement is the diagnosis |
| `jvm_gc_pause_seconds` climbing with heap | Memory pressure, not load |
| `tomcat_threads_busy` near max, everything else calm | Thread exhaustion — the failure whose whole signature is that CPU, heap and the database all look healthy |
| `grpc_client_channel_state{state="TRANSIENT_FAILURE"}` | The service-to-service hop, not the database |
| Latency high only *through* the edge | Kong's rate limiter. It allows 120/min per route |

Then: HTTP dashboard → traces (slowest spans) → the log line by `trace_id` → Pyroscope for the method.
[Observability.md §5](Observability.md#5-where-to-look-by-symptom) is the full symptom map.

### 6.4 "The circuit breaker is open"

`GrpcCircuitBreakerOpen` fires after 1 minute of `resilience4j_circuitbreaker_state{state="open"} == 1`.

```bash
curl -s http://localhost:8081/actuator/metrics/resilience4j.circuitbreaker.state
./scripts/chaos.sh list                  # is a toxic on inventory-grpc the cause?
```

It trips on **slow calls as well as errors**: 50% failure rate, or 80% of calls slower than 250 ms,
over a sliding window of 20. It stays open for 10 s, then admits 3 half-open calls.

An open breaker is working as designed. The action is to fix what it is protecting you from, then
watch it close on its own — not to raise the thresholds.

### 6.5 "A container is unhealthy"

```bash
./scripts/infra.sh health
docker inspect --format '{{json .State.Health}}' lab-<name> | head -c 2000
./scripts/infra.sh logs <service>
```

Then check whether it is a dependency rather than the container itself. `depends_on` means an unhealthy
PostgreSQL reports as a failed dependency several services away from the actual cause.

### 6.6 "Disk is filling"

```bash
docker system df                 # images and build cache are the usual answer
docker volume ls | grep lab-
docker system prune              # safe: removes stopped containers, dangling images, build cache
```

`DiskSpaceFilling` (warning, 15 min) is the ticket that stops `DiskSpaceCritical` (critical, 5 min)
from happening. The distinction matters because PostgreSQL and Oracle stop accepting writes when they
cannot extend a datafile, and they do it abruptly rather than degrading.

---

## 7. Data procedures

### Reset everything

```bash
./scripts/infra.sh destroy && ./scripts/infra.sh up
```

Deletes all 19 volumes. This is also the only way to re-run the init scripts that provision database
credentials and the Keycloak realm ([Deployment.md §9](Deployment.md#9-changing-configuration-that-only-applies-once)).

### Reset one datastore

| Datastore | Command | Cost |
| --- | --- | --- |
| Redis | `docker exec lab-redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning FLUSHALL` | A burst of cache misses |
| Kafka | `docker compose down && docker volume rm lab-kafka-data && docker compose up -d` | All events and offsets. `KAFKA_CLUSTER_ID` in `.env` must stay unchanged |
| MinIO | Delete objects via the console | Invoices are rebuilt on demand |
| PostgreSQL / Oracle | No partial reset — `destroy` | Everything |

### Backups

**There are none.** No volume is backed up, no restore has been tested, and there is no RPO or RTO. In
a lab that is a deliberate simplification; anywhere else it is the first gap to close. It is listed in
[Deployment.md §12](Deployment.md#12-what-this-deployment-is-not) for that reason.

---

## 8. Running an experiment

The lab exists to be broken on purpose. The discipline around that is what makes the results mean
anything.

### The protocol

```bash
./scripts/chaos.sh reset                    # 1. known-clean state
./scripts/infra.sh health                   # 2. everything converged
# 3. write down what you expect to see, per signal, BEFORE running anything
./scripts/scenario.sh <name>                # 4. inject, hold, heal, report
./scripts/chaos.sh list                     # 5. confirm the heal actually healed
```

Step 3 is not ceremony. A scenario whose expected signals were written after seeing the output confirms
nothing. [FailureSimulation.md](FailureSimulation.md) states the expectation for all 13 scenarios
before the run, and records where observed behaviour differed — the gaps are worth more than the
scenarios that went to plan.

### One command per experiment

```bash
./scripts/scenario.sh list                  # every scenario, one line each
./scripts/scenario.sh slow-request          # inject, hold 90s, heal, print what to look at
./scripts/scenario.sh memory-leak --under-load
./scripts/scenario.sh all                   # every scenario in sequence
```

`scenario.sh` heals on exit, including Ctrl-C. `--under-load` runs the fault while k6 offers traffic,
which matters more than it looks: a fault injected into an idle system tells you what the mechanism
does; a fault injected into a saturated one tells you what it does when it matters, and the two answers
are frequently different.

### Faults by hand

```bash
# Network — Toxiproxy, no restart, effective on the next connection
./scripts/chaos.sh slow postgres 400        # +400 ms on every database response
./scripts/chaos.sh blackhole oracle         # accept connections, answer nothing
./scripts/chaos.sh down redis               # refuse connections outright
./scripts/chaos.sh reset-peer kafka 100     # RST after 100 ms
./scripts/chaos.sh throttle minio 64        # cap to 64 KB/s
./scripts/chaos.sh heal postgres            # remove that proxy's toxics

# In-process — the ones a proxy cannot produce
./scripts/chaos.sh app cpu order 4 60       # 4 threads, 60 s
./scripts/chaos.sh app memory order 256     # retain 256 MB until released
./scripts/chaos.sh app deadlock order       # PERMANENT until restart
./scripts/chaos.sh app dlq inventory        # poison message → dead letter
./scripts/chaos.sh app status               # what each service currently has injected
```

Proxies: `postgres`, `oracle`, `redis`, `kafka`, `minio`, `inventory-http`, `inventory-grpc`.
Services: `order`, `inventory`.

**Every in-process toggle expires on its own** — `app.chaos.default-ttl` is 5 minutes, capped at 30 —
so "forgot to reset" is a lab that recovers by itself rather than a debugging session three days later.
`deadlock` is the exception and needs a restart.

### Load

```bash
./scripts/load.sh smoke      # 1 VU, 1 min — is the system wired up?
./scripts/load.sh load       # 10 orders/s for 5 min
./scripts/load.sh stress     # climb to 100/s until something gives
./scripts/load.sh spike      # idle, then 80/s in ten seconds
./scripts/load.sh soak       # 5/s for two hours — finds what accumulates

RATE=20 DURATION=10m ./scripts/load.sh load        # overrides
BASE_URL=http://nginx ./scripts/load.sh load       # through the edge: measures the rate limiter
```

Results remote-write into the same Prometheus that scrapes the platform, so the load and its effect
share one time axis. Tuning and interpretation are in [Performance.md](Performance.md).

---

## 9. Change procedures

| Change | Procedure | Verify |
| --- | --- | --- |
| Kong route, plugin, rate limit | Edit `infrastructure/kong/kong.yml` → `gateway.sh validate` → `gateway.sh reload` | `gateway.sh routes`, then an actual request |
| Nginx header or timeout | Edit `infrastructure/nginx/` → `gateway.sh reload` | `curl -i http://localhost/healthz` |
| Shared config value | `consul kv put config/application/data` | `/actuator/info` on both services |
| Alert rule | Edit `infrastructure/prometheus/rules/` → `POST /-/reload` | `http://localhost:9090/rules`, and make it fire |
| Alert routing | Edit `infrastructure/alertmanager/alertmanager.yml` → `POST /-/reload` | Mailpit and the webhook sink |
| Dashboard | Edit under `infrastructure/grafana/dashboards/` | Reload the dashboard; every panel must render data, not "No data" |
| Anything in a service | Rebuild and recreate (§3.2) | §3.1 |

**A new alert is not done until it has fired once.** An alert that has never fired is untested — the
expression, the label set, the routing and the receiver are four independent things that can each be
wrong while looking correct.

```bash
# The whole chain, on purpose
./scripts/scenario.sh cpu-spike
# then: http://localhost:9090/alerts → http://localhost:9093 → http://localhost:8025
```

Adding an alert, changing routing, and the monitoring credentials the exporters use are covered in
[Alerting.md §7](Alerting.md#7-operating-it).

---

## 10. Proving the system is healthy again

After any procedure above, this is the sequence that closes it out.

```bash
./scripts/chaos.sh reset                   # nothing injected
./scripts/infra.sh health                  # every container converged
./scripts/gateway.sh status                # routes present, upstreams healthy
curl -s 'http://localhost:9090/api/v1/query?query=up==0'          # no target down
curl -s 'http://localhost:9090/api/v1/query?query=ALERTS{alertstate="firing"}'
./scripts/load.sh smoke                    # the whole path, with thresholds
```

A green smoke run after a green health table is the strongest statement this lab can make about
itself: 36 containers converged, the edge authenticating, both databases writable, the broker round
trip closing, and every threshold met.

If `ALERTS` still returns something, read [Alerting.md §6](Alerting.md#6-incident-response) rather than
silencing it. A silence is a decision to stop looking, and it belongs in a ticket.

---

## 11. Known operational quirks

| Quirk | Why | What to do |
| --- | --- | --- |
| Kong keeps a target marked unhealthy after a chaos run | Passive health checks need 3 successes to recover, and active checks run every 10 s | Wait ~30 s, or `gateway.sh reload` |
| `retry-topic` exists and is empty forever | Created by `kafka-init`, never referenced by code. Retries are in-process | Nothing. It is a reserved name, documented here so it is not mistaken for a broken pipeline |
| A toxic survives `infra.sh down` | Toxics live in the Toxiproxy process, so they do **not** survive a restart of that container — but they do survive everything else | `chaos.sh reset` before measuring, always |
| Oracle "hangs" for minutes on first boot | It is initialising the database | `infra.sh logs oracle`, wait for `DATABASE IS READY TO USE` |
| The stack starts but exporters cannot authenticate | `.env` is missing keys `.env.example` defines | `infra.sh` prints the missing list on every command; copy the blocks across |
| A service run from the IDE ships no logs | `run-service.sh` writes to `<repo>/logs`, not the `lab-logs` volume the agents tail | Expected. See [Logging.md](Logging.md) |
| `docker exec` reports a path prefixed `C:/Program Files/Git/` | Git Bash rewrites POSIX-looking arguments | The scripts set `MSYS_NO_PATHCONV=1`; a one-off needs the same, or a leading `//` |

---

## 12. Related documents

| Document | For |
| --- | --- |
| [Alerting.md](Alerting.md) | The alert matrix, routing, and first response per alert |
| [Troubleshooting.md](Troubleshooting.md) | Symptom → cause → check → fix |
| [Deployment.md](Deployment.md) | Bringing it up, configuring it, rolling it back |
| [Performance.md](Performance.md) | Measuring it, and what limits it |
| [Simulation.md](Simulation.md) | Network faults and load, signal by signal |
| [FailureSimulation.md](FailureSimulation.md) | 13 in-process scenarios, plus 2 for the secret store |
| [Observability.md](Observability.md) | Where to look, by symptom |
