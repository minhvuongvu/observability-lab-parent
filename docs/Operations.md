# Operations

Running this system day to day. Start it, stop it, change one piece, read its health, find its logs,
clean up after it — and the gotchas that only show up once you have done all of that.

> **This is the task-shaped guide.** [Runbook.md](Runbook.md) is the same territory organised as
> procedures for an operator on duty; this one is organised for someone learning to operate it, and it
> explains *why* each command behaves as it does.
>
> Every command here was run against a live stack. Outputs shown are real.

---

## 1. The lifecycle

```bash
./scripts/infra.sh up          # build, start, wait until converged
./scripts/infra.sh health      # one line per container
./scripts/infra.sh urls        # where everything lives
./scripts/infra.sh logs kafka  # follow one component
./scripts/infra.sh down        # stop, keep every volume
./scripts/infra.sh destroy     # stop and delete every volume (prompts)
./scripts/infra.sh restart     # down, then up
./scripts/infra.sh build       # rebuild only the two service images
```

Anything `infra.sh` does not recognise is passed straight through to `docker compose` with all five
`-f` flags already applied:

```bash
./scripts/infra.sh ps
./scripts/infra.sh exec redis sh
./scripts/infra.sh top
```

That pass-through is why you almost never need to type the five compose files yourself. When you do —
because you want a Compose flag `infra.sh` does not wrap — work from `docker/compose/`:

```bash
cd docker/compose && docker compose up -d order-service
```

### `up` starts Vault first, and unseals it

Before anything else, `infra.sh up` brings up Vault alone and runs `./scripts/vault.sh bootstrap`
against it — initialise, unseal, seed. Only then does it start the rest of the stack.

This step exists because Vault is **not** running in dev mode. A real Vault boots **sealed on every
start**, and no arrangement of `depends_on` can fix that: something has to present the unseal keys,
and it cannot be a container in the same compose file, because it would need the very keys the seal
is protecting.

Vault's healthcheck passes only when **unsealed**, and both services declare
`vault: condition: service_healthy` — so the ordering is enforced by compose as well as by the script.

The consequence to remember: a bare `docker compose up`, a Docker Desktop restart or a machine reboot
leaves Vault sealed. Nothing appears broken — running services keep serving from the secrets they
already hold — until the next restart of a service, which then cannot start.

```bash
./scripts/vault.sh status     # Sealed  true/false
./scripts/vault.sh unseal
```

`./scripts/chaos.sh reset` also unseals, for the same reason it clears toxics: a fault with no
symptom is the one most likely to be left behind.

### `up` is idempotent, and rebuilds

`infra.sh up` passes `--build` every time. That is deliberate: a stale service image is a debugging
session that starts with entirely correct-looking code. The layer cache makes it nearly free when
nothing changed.

It then polls every 5 s until the stack converges, up to `WAIT_TIMEOUT_SECONDS` (900). Converged
means every long-running container is `healthy` — or `running` with no healthcheck — **and** every
one-shot has exited **0**.

```bash
WAIT_TIMEOUT_SECONDS=1800 ./scripts/infra.sh up   # a genuinely slow machine
```

### down versus destroy

| | Containers | Volumes | Images |
| --- | --- | --- | --- |
| `down` | removed | **kept** | kept |
| `destroy` | removed | **deleted**, after typing `destroy` | kept |

`destroy` is also the only way to re-run the init scripts that provision database credentials and the
Keycloak realm — they run only on an empty volume. See
[Configuration.md §7](Configuration.md#7-changes-that-only-apply-to-an-empty-volume).

---

## 2. Restarting and rebuilding one service

```bash
cd docker/compose
docker compose restart order-service                       # same image, fresh process
docker compose build order-service && docker compose up -d order-service   # new code
```

**What happens around it, and none of it needs your help:**

| Component | Behaviour |
| --- | --- |
| In-flight requests | Drain for up to 30 s (`server.shutdown: graceful`) |
| Kong | Marks the target unhealthy after 3 failures at 5 s; returns after 2 successes at 10 s |
| Consul | Deregisters on clean shutdown, re-registers on boot |
| Kafka | Group rebalances. Offsets are committed per record, so at most one record replays |
| Outbox | Undelivered rows stay in PostgreSQL and go out on the next 1 s poll |

**There will be a gap.** One replica per service means a restart is an outage of that service for its
duration, and Kong answers 503 while no target is healthy. That is the topology, not a fault.

**Verify it came back:**

```bash
curl -fsS http://localhost:8081/actuator/health/readiness    # {"status":"UP"}
./scripts/gateway.sh health                                  # target HEALTHY again
docker inspect --format '{{.Config.Image}}' lab-order-service
```

That last line matters after a rebuild — it is how you confirm you are running what you just built.

---

## 3. Reading health — what it means and what it does not

Four different things call themselves "health" here, and they answer four different questions.

| Probe | Answers | Made of |
| --- | --- | --- |
| `/actuator/health/liveness` | Should this be **restarted**? | `livenessState` only |
| `/actuator/health/readiness` | Should traffic be **routed** here? | `readinessState`, `db`, `redis` |
| Docker healthcheck | Compose ordering, and Kong routing | **Readiness** |
| Consul check | Should discovery hand this instance out? | `/actuator/health` at the **advertised** address |

```bash
curl -s http://localhost:8081/actuator/health/readiness
curl -s http://localhost:8081/actuator/health | python -m json.tool | head -20
```

**Three consequences worth internalising.**

1. **A broken database does not restart the service.** It makes it *unready*. Restarting a service
   because its database is slow removes the one component that could still serve cached reads.
2. **Consul checks the advertised address, not the process.** For the Inventory Service that address is
   `toxiproxy`. If the *path* is broken the service is unreachable, whatever the process thinks — so
   the check is correct even though it is not checking the process directly.
3. **`management.health.consul.enabled: false`.** Otherwise Consul health-checking `/actuator/health`
   would couple every service's health to the component doing the checking, and a registry blip would
   mark the whole estate down.

### Reading the health table

```bash
./scripts/infra.sh health
```

```
CONTAINER            STATE        HEALTH
lab-order-service    running      healthy
lab-fluent-bit       running      none
lab-kafka-init       exited       exit 0
```

| You see | It means |
| --- | --- |
| `running / healthy` | Passing its declared check |
| `running / none` | **No healthcheck declared.** Not a problem — 9 containers are like this |
| `exited / exit 0` | A one-shot that finished. Correct for `kafka-init`, `minio-init`, `consul-init` |
| `exited / exit N` | A failure. Read its logs |
| `running / unhealthy` | Failing its check — **or the check itself is broken**, see §7 |

A healthy stack right now: **23 healthy, 9 none, 3 exit 0** = 35.

### Health that Docker cannot tell you

Nine containers have no healthcheck, so their liveness is Prometheus' job:

```bash
curl -s 'http://localhost:9090/api/v1/query?query=up==0'
```

**See:** an empty `result` array. Anything in it is a component that is down *and* would otherwise be
invisible — `fluent-bit` most importantly, because a stopped log shipper looks exactly like a quiet
system.

---

## 4. Logs — where they are and how to follow one

Every service writes **the same JSON records to two places**: stdout (so `docker logs` works) and a
file on the shared `lab-logs` volume (so the three shipping agents can tail it).

```bash
# 1. Straight from the container runtime — fastest, no dependencies
docker logs -f lab-order-service
./scripts/infra.sh logs order-service        # same thing, via compose

# 2. The JSON file the shippers read
docker exec lab-order-service tail -f /var/log/lab/order-service.json

# 3. Loki, through Grafana Explore — the only one that can query across services
```

Each record carries 14 fields:

```
@timestamp  level  logger  thread  message  service  environment  version
protocol  request_id  correlation_id  trace_id  span_id  user_id
```

`trace_id` is the one that matters most — it is what turns a log line into a trace.

### Per component

| Component | Command |
| --- | --- |
| Either service | `./scripts/infra.sh logs order-service` |
| Nginx (edge access log, JSON) | `docker logs -f lab-nginx` |
| Kong | `docker logs -f lab-kong` |
| Keycloak | `docker logs -f lab-keycloak` |
| Kafka | `./scripts/infra.sh logs kafka` |
| Oracle (startup progress) | `./scripts/infra.sh logs oracle` |
| A one-shot, after the fact | `docker logs lab-kafka-init` |
| Everything at once | `./scripts/infra.sh logs` |

### Querying across services

In Grafana → Explore → Loki:

```logql
{service="order-service"} |= "ORD-20260722-A7938A48"          # one order
{service=~".+"} | json | level="ERROR"                        # all errors, both services
sum by (service) (count_over_time({service=~".+"}[5m]))       # volume per service
{job="promtail"} != "actuator"                                # one pipeline, minus probe noise
```

**Labels are deliberately few:** `service`, `job`, `level`, `pipeline`, `environment`, `filename`,
`service_name`. Everything else — order number, customer, trace id — lives *in the line* and is
matched with `|=` or parsed with `| json`. Promoting a high-cardinality field to a label would create
one Loki stream per value and take it down. `job` distinguishes the two pipelines carrying the same
records: `promtail` and `fluent-bit`.

### Logs that go nowhere, by design

A service started with `./scripts/run-service.sh` writes to `<repo>/logs`, **not** the `lab-logs`
volume the agents mount. Those lines never reach Loki. That is expected for the IDE debug path, and it
is the usual explanation for "my logs stopped appearing".

---

## 5. Scaling a service, and what breaks

Try it:

```bash
cd docker/compose
docker compose up -d --scale order-service=2 order-service
```

**What you will actually see:**

```
WARNING: The "order-service" service is using the custom container name
"lab-order-service". Docker requires each container to have a unique name.
Remove the custom name to scale the service
```

It does not scale. `container_name` is set, and Compose cannot create two containers with one name.
Scaling requires removing `container_name` **and** the published `ports` block for that service — at
which point it is reachable only inside `lab-net`, which is where Kong, Consul and Prometheus address
it anyway.

Do that, and here is what a second instance actually changes:

| Component | With two instances |
| --- | --- |
| **Consul** | Both register. `query-passing: true` returns only healthy ones |
| **gRPC** | ✅ Spreads. The channel resolves through Consul and balances client-side |
| **Kong** | ❌ Does not spread. `order-service.upstream` has one static target; the second gets no edge traffic until added |
| **Prometheus** | ❌ Does not scrape it. Targets are static in `prometheus.yml` |
| **Kafka** | Rebalances, but each topic has **3 partitions** and each instance runs `concurrency: 3` — so half the listener threads sit idle |
| **Outbox relay** | Both poll the same table. Row locks make it safe; throughput does not double |
| **Databases** | Unchanged. Both instances share PostgreSQL, Oracle and Redis |

**The lesson is the three ❌ rows.** A scale-out is not finished when the process starts — it is
finished when discovery, the load balancer *and* the scrape config know about it. Two of those are
static files here, which is precisely the manual work an orchestrator exists to remove.

---

## 6. Disk, volumes and cleanup

Docker grows quietly. On a machine that has run this lab a few times:

```bash
docker system df
```

```
TYPE            TOTAL     ACTIVE    SIZE      RECLAIMABLE
Images          53        45        32.74GB   27.59GB (84%)
Containers      77        32        407.8MB   211.6MB (51%)
Local Volumes   98        89        4.986GB   48.03MB (0%)
Build Cache     71        0         7.503GB   4.726GB
```

**84% of images reclaimable** is normal after a few rebuilds — each `infra.sh build` leaves the
previous service image dangling.

```bash
docker system prune                 # safe: stopped containers, dangling images, build cache
docker system prune -a              # also every image not used by a running container — forces a re-pull
docker volume ls | grep lab-        # the lab's 19 volumes
```

`docker system prune` never touches named volumes, so your data is safe. To delete data you have to
say so:

```bash
./scripts/infra.sh destroy          # every lab volume, after confirmation
docker volume rm lab-kafka-data     # one of them, stack stopped
```

### What lives where

| Volume | Holds | Safe to delete? |
| --- | --- | --- |
| `lab-postgres-data` | `orderdb` **and Keycloak's realm** | Loses both. Forces a realm re-import |
| `lab-oracle-data` | Stock | Loses it; next `up` re-initialises for several minutes |
| `lab-kafka-data` | Broker log and offsets | Fine, but keep `KAFKA_CLUSTER_ID` unchanged |
| `lab-redis-data` | Cache | **Always safe.** Derived state only |
| `lab-minio-data` | Invoices | Safe; invoices are rebuilt on demand |
| `lab-logs` | The JSON log files | Safe |
| 12 telemetry volumes | Metrics, logs, traces, profiles, dashboards | Safe; loses history |

### Log files grow

The JSON log files are the fastest-growing thing in a running stack — 22 MB for the Order Service
after a few load runs:

```bash
docker exec lab-order-service ls -la /var/log/lab/
```

They roll and compress (`.gz`) automatically. If they do not, a shipper is stuck holding a file handle
open — restart `promtail` and `fluent-bit`.

**There are no backups of anything.** No volume is backed up, no restore has been tested, and there is
no RPO or RTO. Deliberate for a lab, and the first gap to close anywhere else.

---

## 7. Known operational gotchas

Every one of these was hit while writing this guide.

### Kong's health view does not see service-to-service faults

**This is the one that will mislead you.** Kong reaches the Inventory Service **directly**; the Order
Service reaches it **through Toxiproxy**. So:

```bash
./scripts/chaos.sh down inventory-http
./scripts/chaos.sh down inventory-grpc
curl -s http://localhost:8001/upstreams/inventory-service.upstream/health
```

**See:** `HEALTHY`, during the fault. The public stock API also keeps answering `200`.

Kong is not wrong — *its* path to Inventory is fine. It simply cannot see the path that is broken.
Using Kong's health page to answer "is Inventory reachable?" gives the wrong answer with total
confidence.

### Kong's balancer lags a restart by up to ~20 s

After a service restart, Kong needs **2 successes at 10 s intervals** before it routes again. A 503
immediately after a restart is the balancer catching up, not a failure. If it persists:

```bash
./scripts/gateway.sh health
./scripts/gateway.sh reload     # waits for configuration_hash to change before returning
```

Always `./scripts/gateway.sh validate` before `reload`. Kong is DB-less, so `kong.yml` *is* the
gateway's entire state, and a typo means a gateway that refuses every request.

### Healthchecks are baked in at container creation

Editing a compose file does not change a running container, and neither does `restart`. Only
*recreation* applies a new healthcheck — and `docker ps` shows uptime since last **start**, so a
container reading `Up 30 minutes` can be days old.

```bash
docker inspect --format '{{.Created}}' lab-promtail                        # not the same as uptime
docker inspect --format '{{json .Config.Healthcheck.Test}}' lab-promtail   # what is running
docker compose up -d --force-recreate promtail                             # apply the declared one
```

Symptom: a container permanently `unhealthy` with an error naming a binary the image does not have
(`wget: not found`). Left alone it means `infra.sh up` can **never** converge.

### Toxics survive almost everything

A toxic lives in the Toxiproxy *process*. It survives `infra.sh down`, a service restart, and your
lunch break. It does not survive recreating the `toxiproxy` container.

```bash
./scripts/chaos.sh list     # ALWAYS, before measuring anything
./scripts/chaos.sh reset    # clears both fault levels, everywhere
```

In-process chaos toggles expire on their own after `app.chaos.default-ttl` (5 min, capped at 30).
`deadlock` is the exception — it is permanent until the service restarts, and says so before it runs.

### A metric window outlasts the fix

After healing a fault, `lab:http_latency_p99:5m` stays high for **five more minutes** — the recording
rule's window still contains the slow requests. Measured: p99 was 9179 ms 90 s after the fix, and
20 ms once the window rolled.

Use a shorter window when you want to see recovery:

```promql
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{service="order-service"}[1m])))
```

### `.env` staleness the check cannot catch

`infra.sh` warns about *missing* keys. It cannot warn about keys whose **meaning changed** —
`REDIS_PORT` and `KEYCLOAK_ISSUER` both did. See
[Configuration.md §8](Configuration.md#8-what-must-change-together).

### On Windows

```bash
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
```

Without it, Git Bash rewrites container paths in `docker exec` into `C:/Program Files/Git/...`. The
repo's scripts set it themselves; your own commands need it.

### Kafka 4.x moved a class every tutorial still shows

```bash
# correct
docker exec lab-kafka /opt/kafka/bin/kafka-run-class.sh org.apache.kafka.tools.GetOffsetShell \
  --bootstrap-server kafka:9092 --topic dead-letter-topic
```

The old `kafka.tools.GetOffsetShell` throws `ClassNotFoundException` — and with stderr redirected, a
piped count reports **0**, which reads exactly like an empty topic.

Likewise `kafka-console-consumer.sh` **blocks forever on an empty topic** unless you pass
`--timeout-ms`.

---

## 8. A working day, in commands

```bash
# morning
./scripts/infra.sh up
./scripts/infra.sh health
./scripts/chaos.sh list                    # is anything left over from yesterday?

# change a service
cd docker/compose && docker compose build order-service && docker compose up -d order-service
curl -fsS http://localhost:8081/actuator/health/readiness

# change gateway policy — no restart
./scripts/gateway.sh validate && ./scripts/gateway.sh reload

# change shared config — no restart
docker exec -i lab-consul consul kv put config/application/data - < some.yaml
curl -s http://localhost:8081/actuator/info

# measure something
./scripts/chaos.sh reset && ./scripts/load.sh load

# run an experiment
./scripts/scenario.sh list
./scripts/scenario.sh slow-request --under-load

# close out
./scripts/chaos.sh reset
./scripts/infra.sh health
./scripts/load.sh smoke                    # the acceptance test
./scripts/infra.sh down
```

---

## 9. Related

| Document | For |
| --- | --- |
| [Runbook.md](Runbook.md) | The same operations as on-duty procedures, with verification steps |
| [Troubleshooting.md](Troubleshooting.md) | When something is wrong and you do not know what |
| [Configuration.md](Configuration.md) | Every knob, and what moves when it does |
| [Debugging.md](Debugging.md) | Worked investigations, start to finish |
| [Deployment.md](Deployment.md) | The deployment surface and what it is not |
| [Infrastructure.md](Infrastructure.md) | What each component is |
| [Alerting.md](Alerting.md) | The alert matrix and first response |
