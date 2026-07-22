# Troubleshooting

Symptom → cause → check → fix. Organised by what a person would *say*, not by which component is at
fault, because the component is what is being looked for.

> **Related.** [Alerting.md §6](Alerting.md#6-incident-response) is first response per *alert*.
> [Runbook.md](Runbook.md) is procedures for known operations. This document is for when it is not
> yet known what is wrong.
>
> [Infrastructure.md §8](Infrastructure.md#8-troubleshooting) covers the same startup problems more
> briefly; the long form is here.

---

## 1. Triage — four questions, in this order

Answer these before opening a dashboard. Each one eliminates a whole class of cause, and three of the
four cost under a second.

```bash
# 1. Is a fault still injected?  This is the most expensive mistake in this lab.
./scripts/chaos.sh list && ./scripts/chaos.sh app status

# 2. Is every container converged?
./scripts/infra.sh health

# 3. Is any scrape target down?  A missing exporter cannot fire the alert you are waiting for.
curl -s 'http://localhost:9090/api/v1/query?query=up==0'

# 4. Is this a path problem or a component problem?
#    The exporters bypass Toxiproxy. If pg_up==1 while the service reports timeouts,
#    the fault is in the PATH, and the disagreement is the diagnosis.
```

Question 1 catches more than it should. A 400 ms latency toxic on `postgres` produces a system that
looks broken in precisely the way a regression looks broken, and no signal anywhere says "somebody did
this on purpose".

**`./scripts/chaos.sh reset` clears both fault levels, everywhere.** Run it before concluding anything.

---

## 2. The stack will not start

### Oracle never becomes healthy

**Almost always memory.** Oracle is capped at 2560M and needs most of it; a first initialisation
genuinely takes minutes.

```bash
./scripts/infra.sh logs oracle | grep -i 'ready to use\|error\|ORA-'
```

Look for `DATABASE IS READY TO USE`. Its healthcheck allows `start_period: 180s` plus 40 retries at
15 s — roughly ten minutes — and that is deliberate. Do not shorten it because it looks stuck.

If it fails repeatedly, raise Docker's memory allocation. Below 8 GB Oracle will not start, and the
Inventory Service cannot become ready until it has.

### Keycloak restarts in a loop

Nearly always PostgreSQL. Check that `keycloakdb` was actually created:

```bash
docker exec lab-postgres psql -U postgres -lqt | cut -d'|' -f1
```

If it is absent, the PostgreSQL volume was initialised before the init script existed, so the script
never ran — init scripts only run on an **empty** data directory.

```bash
./scripts/infra.sh destroy && ./scripts/infra.sh up
```

### A one-shot container exited non-zero

`kafka-init`, `minio-init` and `consul-init` must exit **0** or `infra.sh up` refuses to call the stack
ready. That is on purpose: a failed bucket or topic setup passing for a healthy stack surfaces much
later, somewhere else entirely.

```bash
./scripts/infra.sh logs kafka-init
docker inspect --format '{{.State.ExitCode}}' lab-minio-init
```

### `up` times out after 900 s

It prints the state of every container. Read the health column first, then the logs of whichever is
not healthy. `WAIT_TIMEOUT_SECONDS=1800 ./scripts/infra.sh up` extends it if the machine is genuinely
slow rather than broken.

### Port already in use

Every port is a variable in `.env` — change it there, not in a compose file, so the override stays
machine-local and out of version control.

| Port | Usual owner |
| --- | --- |
| 80 | On Windows, IIS or the kernel HTTP stack. `NGINX_HTTP_PORT` is the first thing to change there |
| 5432 | A natively installed PostgreSQL |
| 6379 | A natively installed Redis |
| 8080 | Another Keycloak, or any JVM application |
| 9200 | A natively installed Elasticsearch |

Moving the lab is almost always better than stopping whatever already owns the port.
`./scripts/infra.sh urls` prints the effective addresses, so it is the fastest way to see what a
remapped stack actually listens on.

### An init script fails with `bad interpreter` or shows `^M`

The file was checked out with Windows line endings and the container cannot execute it.
[`.gitattributes`](../.gitattributes) exists to prevent exactly this.

```bash
git add --renormalize . && git checkout .
./scripts/infra.sh destroy && ./scripts/infra.sh up
```

The symptom is a database that comes up with no schema, or Kafka with no topics, and one line about it
deep in a container log.

### `up` fails with `decoding failed` and `invalid size: ''`

```
'services[order-service].deploy.resources.limits.cpus' strconv.ParseFloat: parsing "": invalid syntax
'services[order-service].deploy.resources.limits.memory' invalid size: ''
```

`.env` is stale and is missing the keys that feed the resource limits. Compose cannot decode the file,
so **nothing starts** — which is why `order-service`, `inventory-service` and `toxiproxy` are absent
while the rest of the stack looks fine (they were started by an older, shorter `COMPOSE_FILE`).

`infra.sh` prints the missing list above this, on every command:

```
WARNING: docker/compose/.env is missing 27 key(s) that .env.example defines:
```

### The stack starts, but an exporter cannot authenticate, or a port is published on `:0`

The same cause, milder outcome. Where a missing key feeds an environment variable or a published port
rather than a resource limit, Compose substitutes an **empty string** and carries on, so the stack
starts and misbehaves instead of failing.

### Everything looks configured and every request still 401s

**The staleness check compares key names, not meanings.** A key that is present but whose meaning
changed passes silently, and it is the worst of the three cases. Two have changed here:

| Key | Used to mean | Now means |
| --- | --- | --- |
| `REDIS_PORT` | The published host port | The **in-network Toxiproxy listener** (`16379`); the published port moved to `REDIS_HOST_PORT` |
| `KEYCLOAK_ISSUER` | `http://localhost:8080/…` | `http://keycloak:8080/…` — what Kong's pinned consumer key matches |

A `.env` predating the containerisation change carries both and is not flagged.

### The fix for all three

```bash
cd docker/compose
cp .env ".env.stale-$(date +%Y%m%d-%H%M%S)"    # keep it — it holds your overrides
cp .env.example .env
# re-apply only the overrides you know you made; a remapped published port is the usual one
```

Copying the missing blocks across by hand also works for the first two cases, and does not fix the
third.

---

## 3. A service will not start or stay up

### Flyway refuses to migrate

`baseline-on-migrate: false` is deliberate: an existing database with no Flyway history is a situation
that needs a human, not a silent assumption. The message names the schema and the version.

If the schema is expendable: `./scripts/infra.sh destroy`.

### Hibernate reports a schema validation failure

`ddl-auto: validate` — drift between the entity model and the migrations is a **startup failure** by
design, not something discovered at the first query. The fix is a new migration, never a change to
`ddl-auto`.

### A container is permanently unhealthy, and the check names a binary the image does not have

```
$ docker inspect --format '{{json .State.Health}}' lab-promtail
  "Output": "/bin/sh: 1: wget: not found"
$ docker inspect --format '{{json .State.Health}}' lab-fluent-bit
  "Output": "exec: \"/bin/sh\": stat /bin/sh: no such file or directory"
```

**Healthchecks are baked into a container when it is created.** Editing the compose file does not
change a running container, and neither does `docker restart` — only *recreation* applies the new
definition. A container created weeks ago keeps the healthcheck it was born with, however many times
it has been restarted since. `docker ps` shows uptime since the last start, so a container reading
`Up 30 minutes` can easily have been created last week.

Confirm by comparing the two:

```bash
docker inspect --format '{{json .Config.Healthcheck.Test}}' lab-promtail   # what is running
grep -A6 'promtail:' -n docker/compose/docker-compose.observability.yml    # what is declared
docker inspect --format '{{.Created}}' lab-promtail                        # not the same as uptime
```

Fix by recreating:

```bash
cd docker/compose
docker compose up -d --force-recreate promtail fluent-bit pyroscope
```

**Why this matters more than a cosmetic status.** `infra.sh up` waits for *every* long-running
container to be healthy before it reports the stack ready. One container stuck unhealthy for a reason
that no longer exists means `up` can never converge and always times out at 900 s — with a health
table that gives no hint the check itself is the problem.

Three images in this stack cannot run a shell-based check at all, which is why their current
definitions avoid one: `promtail` uses `bash` with `/dev/tcp` (no `wget` in the image), `pyroscope`
uses the `profilecli` binary it ships, and `fluent-bit` has **no healthcheck at all** — it is watched
by Prometheus scraping `fluent-bit:2020` instead, because `fluent-bit --version` would prove the
binary is intact rather than that logs are being shipped, and would report healthy through a total
outage.

### The container is healthy but Kong returns 503

Kong's active health check has to agree. It needs 2 successes at 10 s intervals to mark a target
healthy again, so there is a lag of up to ~20 s after a restart.

```bash
./scripts/gateway.sh health
```

If it stays unhealthy, check that the target address in `kong.yml` (`order-service:8081`) resolves —
it is a compose name on `lab-net`, not a published port.

### The service exits immediately with no useful log

`-XX:+ExitOnOutOfMemoryError` is set. An OOM kill and a startup failure look identical from outside.

```bash
docker inspect --format '{{.State.OOMKilled}} {{.State.ExitCode}}' lab-order-service
```

`OOMKilled=true` means the container hit its memory limit. The heap is `MaxRAMPercentage=70` of that
limit, so raising `ORDER_SERVICE_MEMORY` raises both — and invalidates the calibrated k6 defaults.

---

## 4. Authentication and authorization

This section exists because the failures here are the ones that look like something else.

### 401 from the edge with a token that "should work"

**The issuer.** Keycloak derives the `iss` claim from the `Host` header of the request that minted the
token. A token fetched from `localhost:8080` carries a different issuer than one fetched from
`keycloak:8080`, and Kong's consumer key is `http://keycloak:8080/realms/observability` — the
in-network form.

```bash
# decode the payload of whatever token you have
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | tr ',' '\n' | grep iss
```

`scripts/token.sh` sends `Host: keycloak:8080` precisely so a token minted from the host is
byte-identical in its issuer to one minted from inside the network. **Use it.** A token fetched with a
hand-written `curl` against `localhost:8080` will be rejected, and the 401 looks like an authorization
bug when it is a hostname.

The other issuer trap: changing `KEYCLOAK_REALM` in `.env` without changing the consumer `key` in
`infrastructure/kong/kong.yml` rejects every token as coming from an unknown issuer.

### 401 that is actually expiry

`accessTokenLifespan` is **300 seconds**. A token pasted into a terminal five minutes ago is expired,
and Kong verifies `exp`. Mint a new one.

### 403 where 200 was expected

The role matrix, enforced independently by the services:

| Path | Required |
| --- | --- |
| `/actuator/**`, `/v3/api-docs/**`, `/swagger-ui*`, `/error` | Nothing |
| `/api/v1/chaos/**` — every method | `ADMIN` |
| `DELETE /api/**` | `ADMIN` |
| Every other `/api/**` | `USER` or `ADMIN` |

`alice` is a `USER`; `manager` is `ADMIN` *and* `USER`. `./scripts/token.sh manager` is the fix for a
403 on a `DELETE`.

### 401 from the service but not from the gateway, or the reverse

That is the design working. Kong verifies the signature against a **static** pinned public key selected
by `iss`; the services verify against the realm's **JWKS endpoint** and additionally enforce per-role
authorization. They fail closed independently, so they can disagree:

| Kong says | Service says | Cause |
| --- | --- | --- |
| 401 | — | Signature does not verify against the pinned key, or `iss` is unknown, or `exp` passed |
| 200 | 401 | The realm rotated its signing key and `kong.yml` still holds the old public half — or the reverse |
| 200 | 403 | Authenticated, but the role is insufficient. Only the service knows this |

If the realm's RSA key was regenerated, `kong.yml`'s `rsa_public_key` must be updated in the same
change. The realm pins `rsa-lab-fixed` specifically so this does not happen on every rebuild.

### Everything returns 401 right after a `destroy`

Keycloak re-imported the realm. If the pinned key was somehow not applied, the public half in
`kong.yml` no longer matches. Confirm with:

```bash
curl -s http://localhost:8080/realms/observability/protocol/openid-connect/certs
```

### gRPC calls fail with `UNAUTHENTICATED`

The gRPC port authenticates every call with the same `JwtDecoder` the HTTP resource server uses.
Publishing the port is not what protects it, and binding to `0.0.0.0` inside the container does not
weaken it. A `grpcurl` from the host needs a bearer token in the metadata.

---

## 5. Addressing, ports and the network

### The rule that resolves most of these

> **A published port is for a person. Nothing in the stack uses one.** Every component addresses every
> other by its compose name on `lab-net`.

So `ORDER_DB_PORT=15432` is not PostgreSQL's port — it is Toxiproxy's listener for PostgreSQL, on
`lab-net`. `POSTGRES_PORT=5432` is the host-side publication, and nothing in the stack reads it.
Changing a published port can inconvenience a human and cannot break the system.

### A service connects to Kafka and immediately drops

**Advertised listeners.** A Kafka client bootstraps against one address and is then told by the broker
which address to actually use.

| Client is | Must bootstrap against | Broker advertises |
| --- | --- | --- |
| A container on `lab-net` | `kafka:9092` | `kafka:9092` |
| Something on the host (IDE) | `localhost:29092` | `localhost:29092` |
| Going through the fault proxy | `toxiproxy:19092` | `toxiproxy:19092` |

Using the wrong one produces a connection that establishes and then fails, because the broker hands
back an address the client cannot reach.

The `PROXY` listener exists for exactly this reason: proxying `:9092` would hand the client `kafka:9092`
on the second connection and the proxy would be bypassed — silently, so injected Kafka faults would
appear to do nothing at all.

### A toxic on Kafka appears to do nothing

Check `KAFKA_BOOTSTRAP_SERVERS` is `toxiproxy:19092` and not `kafka:9092`. If a service bootstraps
directly, no Kafka toxic can ever affect it.

```bash
docker exec lab-order-service env | grep KAFKA_BOOTSTRAP
```

### `docker exec` reports a path prefixed with `C:/Program Files/Git/`

Git Bash rewrites arguments that look like absolute POSIX paths into Windows paths before handing them
to a program. The scripts here already set `MSYS_NO_PATHCONV=1` and `MSYS2_ARG_CONV_EXCL='*'`; a one-off
command needs the same, or a leading double slash (`//opt/kong/kong.yml`).

### Nothing on the host can reach anything

`BIND_HOST` is `127.0.0.1`. That is correct and should stay that way — the Toxiproxy control API on
`:8474` is unauthenticated and can break every dependency in the stack. If the stack must be reachable
from another machine, that is a decision to make deliberately, and §12 of
[Deployment.md](Deployment.md#12-what-this-deployment-is-not) is the list of what else has to change
first.

---

## 6. Requests fail or are slow

### 429 from the edge

Kong rate-limits each route to **120 requests per minute**, 3000 per hour, `policy: local`. A k6 run
aimed at `http://nginx` measures the rate limiter and little else — which is a worthwhile experiment
and a poor default, so `K6_BASE_URL` points straight at the service.

```bash
BASE_URL=http://nginx ./scripts/load.sh load     # deliberately measures the limiter
```

### 413 or a truncated body

Two independent ceilings, both at 1 MB: `client_max_body_size 1m` in Nginx and the
`request-size-limiting` plugin in Kong. The outermost hop is the cheapest place to refuse a body
nobody downstream should have to buffer.

### 504 from the gateway

The timeout budget is layered so that the innermost component reports the failure:

```
Nginx  proxy_read_timeout   20s
  Kong read_timeout         15s        ← a hung upstream surfaces here
    Feign read-timeout       5s
      gRPC BatchCheckStock 300ms
        Oracle query       250ms
```

A 504 at the edge means the service did not answer within 15 s. Which layer actually stalled is a
question for the trace, not the status code.

### Requests queue and latency climbs, but CPU and the database look fine

Two candidates, and they are distinguishable:

| Metric | Meaning |
| --- | --- |
| `hikaricp_connections_pending > 0` | Threads waiting for one of the **10** database connections. `DatabasePoolExhausted` fires after 2 minutes |
| `tomcat_threads_busy` near max | Thread exhaustion. Its whole signature is that CPU, heap and the database all look healthy — which is why `server.tomcat.mbeanregistry.enabled: true` is set, or the meters would not exist at all |

The pool is the first thing that runs out in this lab. That is calibrated, not accidental — see
[Performance.md §5](Performance.md#5-what-runs-out-first).

### An order returns 201 and stays PENDING

Not a failure. `201` means **accepted**, not fulfilled: the order is `PENDING` until the Inventory
Service decides, which is what lets orders be taken while Inventory is down.

It becomes `CONFIRMED` or `REJECTED` a moment later. If it does not, work through
[Runbook.md §6.1](Runbook.md#61-orders-are-stuck-pending) — five checks, in the order that costs least.

### A gRPC call fails with `DEADLINE_EXCEEDED`

The deadlines are deliberately tight, and each is smaller than its caller's budget:

| RPC | Deadline |
| --- | --- |
| `CheckStock` | 200 ms |
| `BatchCheckStock` | 300 ms |
| `ReserveStock` (express path) | 200 ms |

Missing the express reservation deadline costs nothing but a fall-through to the Kafka path, which is
the invariant the system had before gRPC existed. `GrpcDeadlineExceededRising` is a warning for that
reason.

### The circuit breaker keeps opening

It trips on **slow calls as well as errors**: 50% failure rate, or 80% of calls slower than 250 ms,
over a sliding window of 20. A dependency answering every call in five seconds is as damaging as one
returning errors, and a pure error-rate breaker never notices.

An open breaker is working. Fix what it is protecting you from; do not raise the threshold.

---

## 7. Data layer

### PostgreSQL

| Symptom | Check |
| --- | --- |
| `PostgresDown` but `psql` works | The exporter's credential. `MONITOR_DB_USER` is created only on an empty volume |
| Connections exhausted | `SELECT count(*), state FROM pg_stat_activity GROUP BY state;` — the app pool is 10 |
| Slow queries | `PostgresSlowQueries` fires on the exporter's view; the trace will name the statement |

### Oracle

| Symptom | Check |
| --- | --- |
| Will not start | Memory. See §2 |
| `oracledb_up == 0` | `ORACLE_MONITOR_USER`, created in the PDB only on first initialisation |
| `ORA-` errors on connect | The service address is `toxiproxy:11521`, and the PDB name is `FREEPDB1` |

### Redis

| Symptom | Check |
| --- | --- |
| `NOAUTH` | The password arrives as a command argument, not from `redis.conf` — a config file in the repository must never contain a credential |
| Cache misses on everything | Redis was flushed, or a `chaos.sh app cache <svc> miss` toggle is still on |
| `RedisMemoryHigh` | Eviction policy and TTLs — orders cache at 10 m |

Redis holds only derived state. `FLUSHALL` costs a burst of misses and nothing else, which makes it the
cheapest way to reproduce a cold-cache latency profile.

### MinIO

| Symptom | Check |
| --- | --- |
| Invoice upload fails | The services authenticate as `MINIO_APP_USER`, scoped to the invoice bucket. Root credentials are not used and should not be substituted |
| A signed URL 403s | `app.invoice.url-validity` is 15 minutes. Short on purpose: a URL leaked through a log or a referrer header stops working quickly |
| The object is missing | The invoice is rebuilt on demand. A missing object degrades to a slower first read |

---

## 8. Messaging

### Consumer lag is growing

```bash
docker exec lab-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --all-groups
```

| Lag pattern | Cause |
| --- | --- |
| Growing steadily on all partitions | The consumer is slower than the producer. `concurrency: 3` already matches the 3 partitions, so more threads will not help — the work per record is the cap |
| **Stuck at a constant small number on one partition** | A record that cannot be processed *and* cannot be dead-lettered. It blocks that partition. This is the failure mode that hid a broken DLQ path for several steps |
| Zero members | `KafkaConsumerGroupEmpty` — the consumer is down, or the group id changed |

`KafkaConsumerLagGrowing` is `info`; `KafkaConsumerLagHigh` is a warning at 10 minutes.

### `retry-topic` is always empty

**Expected.** It is created by `kafka-init` and referenced by no code and no configuration. Retries are
in-process: `FixedBackOff`, 3 attempts, 1 second apart, then straight to `dead-letter-topic`. The topic
name is reserved and documented here so an empty partition list is not mistaken for a broken pipeline.

### A message went to the dead-letter topic immediately, with no retries

Correct behaviour. `BusinessException` and `ValidationException` are registered as **not retryable** —
a rule that refused once refuses identically every time, and retrying it wastes the budget while
blocking the partition behind it.

### Changing a consumer group id replayed everything

`auto-offset-reset: earliest`. The group id is the unit of offset tracking; a new one has no committed
offsets and reads from the beginning. For `order-inventory-updated-group` that is deliberate — a
settlement the service never saw would leave an order `PENDING` forever.

---

## 9. The observability pipeline

Each signal has a chain, and troubleshooting means finding the first broken link. The chains:

```
logs      service → /var/log/lab/<service>.json → Promtail ┐
                                                Fluent Bit ┼→ Loki → Grafana
                                                  Fluentd  ┘→ OpenSearch  (search profile)
metrics   service /actuator/prometheus → Prometheus → Grafana
                                       └→ remote_write → VictoriaMetrics
traces    service (OTel agent) → OTLP :4317 → Collector → Tempo / Jaeger / Zipkin → Grafana
profiles  service (Pyroscope agent) → Pyroscope :4040 → Grafana
```

### No logs in Grafana

Walk the chain from the source:

```bash
# 1. Is the service writing at all?
docker exec lab-order-service ls -la /var/log/lab/

# 2. Is a shipper reading?
./scripts/infra.sh logs promtail | tail -30
curl -s http://localhost:9090/api/v1/query?query=up | grep -o 'promtail[^}]*'

# 3. Is Loki receiving?
curl -s 'http://localhost:3100/loki/api/v1/labels'
```

Common causes, in order of frequency:

| Cause | Tell |
| --- | --- |
| The service was started by `run-service.sh` | It writes to `<repo>/logs`, **not** the `lab-logs` volume the agents mount. Those lines are shipped nowhere, by design |
| The profile is `local` | `local` writes a human-readable console and JSON only to the file. Containers run `dev`, where both carry JSON |
| Loki rejected the labels | High-cardinality labels are dropped by the pipeline stage on purpose; only low-cardinality fields are promoted |
| `LogPipelineSilent` fired | The Grafana rule for exactly this — a pipeline that stops is invisible otherwise |

### No metrics

```bash
curl -s http://localhost:8081/actuator/prometheus | head -5      # is the service exposing?
curl -s 'http://localhost:9090/api/v1/query?query=up==0'          # is Prometheus scraping?
```

`management.endpoints.web.exposure.include` is deliberately narrow: `health,info,metrics,prometheus`,
never `*`. If a meter is missing, check it is not simply unexposed.

A meter that does not exist at all is different from a meter reading zero. `tomcat_threads_*` requires
`server.tomcat.mbeanregistry.enabled: true`; without it `TomcatThreadPoolSaturated` is not quiet, it is
*unable to fire*.

### No traces

```bash
docker exec lab-order-service env | grep OTEL_          # agent configuration
./scripts/infra.sh logs otel-collector | tail -30
curl -s http://localhost:55679/debug/tracez             # collector zPages
```

| Cause | Tell |
| --- | --- |
| The agent is not attached | `JAVA_TOOL_OPTIONS` in `docker-compose.services.yml` — dropping a line here silently removes the agent |
| Exporting to the wrong place | `OTEL_EXPORTER_OTLP_ENDPOINT` must be `http://otel-collector:4317`, a name on `lab-net` |
| Metrics or logs exported twice | `OTEL_METRICS_EXPORTER=none` and `OTEL_LOGS_EXPORTER=none` are deliberate — Prometheus scrapes the metrics and the file pipeline carries the logs |
| Traces exist but do not join across Kafka | That is a span **link**, not a parent-child edge. See [Tracing.md](Tracing.md) |

### No profiles, or an empty flame graph

`PYROSCOPE_PROFILER_EVENT` is `itimer`, not `cpu`. `itimer` samples on a wall clock so blocked time is
attributed too — on a service that spends its life waiting on a database, `cpu` produces a flame graph
of almost nothing, which is exactly the case the simulation stack manufactures.

`PYROSCOPE_FORMAT: jfr` matters as well: with the collapsed format the agent records exactly one event
type, so choosing CPU means giving up allocation and locks.

**Before concluding profiles are missing, check you are asking the right API.** This is Grafana
Pyroscope 1.x, not the original Pyroscope OSS, and the endpoint most search results show was removed:

```bash
curl -s http://localhost:4040/api/apps                      # 404 {"code":5,"message":"Not Found"} — v0 API
curl -s -X POST http://localhost:4040/querier.v1.QuerierService/LabelValues \
  -H 'content-type: application/json' -d '{"name":"service_name"}'
#   -> {"names":["inventory-service","order-service","pyroscope"]}

curl -s "http://localhost:4040/pyroscope/render?query=process_cpu:cpu:nanoseconds:cpu:nanoseconds\
{service_name=\"order-service\"}&from=$(( $(date +%s) - 1800 ))&until=$(date +%s)&format=json"
```

A 404 from `/api/apps` says nothing about whether profiles are arriving. The agent's own answer is in
the service log — it prints its resolved `Config{…}` at startup, including `agentEnabled`,
`serverAddress` and `profilingEvent`:

```bash
docker logs lab-order-service 2>&1 | grep -i pyroscope | tail -3
```

### A dashboard panel shows "No data"

A healthy system renders **zero**, not "No data". "No data" means the query names a series that does not
exist — a typo, a renamed label, or a meter that was never registered. Check the query in Prometheus
directly before assuming the system is idle.

---

## 10. The simulation stack

### A toxic seems to have no effect

| Check | Why |
| --- | --- |
| Does the service address the proxy at all? | `docker exec lab-order-service env \| grep -E 'DB_HOST\|REDIS_HOST\|KAFKA_BOOT'` — a value pointing at the real service name takes the proxy out of that path |
| Is it a new connection? | Toxiproxy takes effect on the **next** connection. A warm pool keeps using established sockets |
| Is it the right proxy? | `inventory-http` and `inventory-grpc` are separate. Kong reaches the Inventory Service directly, so a toxic on `inventory-http` breaks service-to-service calls while the public API keeps answering |
| Kafka specifically | See §5 — bootstrapping past the proxy is silent |

### `chaos.sh app …` returns 401 or 403

The chaos endpoints require `ADMIN` on every method — not just `DELETE`, because a `POST` there can
deadlock the process, so "destructive" is the wrong axis and the verb tells you nothing.

```bash
TOKEN=$(./scripts/token.sh manager)
```

### `chaos.sh app …` returns 404

The endpoints only exist under the `local` and `dev` profiles, and `app.chaos.enabled` is a second,
independent switch. Under `prod` they are not registered at all. A chaos endpoint reachable in
production is not a learning tool; it is a vulnerability.

### The system does not recover after an experiment

```bash
./scripts/chaos.sh reset      # clears both levels, everywhere
```

Every in-process toggle also expires on its own — `default-ttl` 5 minutes, `max-ttl` 30. The exception
is `deadlock`, which is permanent until the service restarts, and says so before it runs.

### k6 results look wrong

```bash
# dropped_iterations in the summary is the first number to check
./scripts/load.sh smoke
```

If k6 itself is the bottleneck, the run measures k6. `K6_MEMORY` is 512M by default. Also confirm no
toxic was active — §1, question 1.

---

## 11. Build failures

| Symptom | Cause | Fix |
| --- | --- | --- |
| Compilation fails with a confusing error about language level | JDK older than 21 | `./scripts/verify-toolchain.sh`; the build enforces the floor and should fail fast with a message |
| `protoc` fails to execute inside the image build | An Alpine base | The Dockerfile uses Debian deliberately — `protoc` from Maven Central is glibc-linked |
| `dependency:go-offline` fails | Known with `protobuf-maven-plugin`, which resolves an OS-classified `protoc` binary | Tolerated on purpose (`\|\| true`). It is a cache warm-up; the package step fetches anything it missed |
| The image builds but runs old code | A stale image | `infra.sh up` passes `--build` for this reason; `docker compose build --no-cache <svc>` forces it |
| The build is slow every time | The BuildKit cache mount is not being reused | Ensure BuildKit is enabled; the Maven repository lives in a cache mount, not a layer |

---

## 12. Signals that mislead

The hardest part of debugging is the signal that looks relevant and is not. These are the ones this
system produces.

| Looks like | Actually is |
| --- | --- |
| The database is down — the service reports timeouts | The **path** is broken. `pg_up == 1` because exporters bypass Toxiproxy. The disagreement is the diagnosis |
| Traffic dropped, so load fell | With a fixed *arrival rate*, offered load is constant. A falling request count means requests are being **dropped**, not withheld |
| CPU is fine, so it is not a CPU problem | `PYROSCOPE_PROFILER_EVENT=itimer` attributes blocked time. A flat CPU graph with rising latency points at waiting, and the flame graph will show *what* is waiting |
| Memory is climbing, so there is a leak | Allocation volume says nothing about what is retained. Use the **live heap** profile, not allocation |
| The service restarted, so it crashed | `ServiceRestarted` is `info` for a reason. It is context. Read the alerts around it |
| Latency is high through the edge only | The rate limiter, not the service. `kong_http_requests_total{code="429"}` |
| An alert is quiet, so that condition is fine | The alert may be unable to fire. Check its exporter is UP — `ExporterDown` exists to make that visible |
| Consumer lag is 1 and static | A blocked partition, not a rounding error. Something cannot be processed *and* cannot be dead-lettered |
| The dashboard says "No data", so nothing is happening | A healthy system renders **zero**. "No data" means the series does not exist |

---

## 13. Escalation — what to capture

If a problem outlives this document, capture these before restarting anything. A restart destroys most
of them.

```bash
./scripts/infra.sh health                > /tmp/health.txt
./scripts/chaos.sh list                  > /tmp/toxics.txt
./scripts/chaos.sh app status            > /tmp/app-chaos.txt
docker stats --no-stream                 > /tmp/stats.txt
./scripts/infra.sh logs --no-follow      > /tmp/logs.txt 2>&1
curl -s 'http://localhost:9090/api/v1/query?query=ALERTS{alertstate="firing"}' > /tmp/alerts.json
docker exec lab-order-service jcmd 1 Thread.print > /tmp/threads.txt
```

The thread dump is the one that cannot be reconstructed afterwards, and it is the only thing that
diagnoses a deadlock.

---

## 14. Related documents

| Document | For |
| --- | --- |
| [Alerting.md](Alerting.md) | First response per alert, and the alert matrix |
| [Runbook.md](Runbook.md) | Procedures, once the cause is known |
| [Deployment.md](Deployment.md) | Configuration surface, startup ordering, rollback |
| [Observability.md](Observability.md) | Where to look, by symptom |
| [Simulation.md](Simulation.md) | Network faults and load, signal by signal |
| [FailureSimulation.md](FailureSimulation.md) | The 13 in-process scenarios |
| [Infrastructure.md](Infrastructure.md) | Component detail and network topology |
