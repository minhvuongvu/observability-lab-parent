# Getting Started

From an empty clone to a working stack, an order you can watch move through it, and the three things
most likely to go wrong on the way.

> **Read this at a keyboard.** It is in order and every command is meant to be run. Each stage says
> what you should **see**, because the hardest part of a first run is telling a slow success from a
> silent failure.
>
> Every command below was executed against a running stack while this was written. Where output is
> shown, it is real output, trimmed.

---

## 1. What you need, and what each thing is actually for

| Requirement | Why it exists |
| --- | --- |
| **Docker Desktop / Engine, with Compose v2** | Runs all 35 containers. The only hard requirement |
| **10 GB allocated to Docker** | Oracle alone is capped at 2.5 GB and will not start below ~8 GB total. This is the number that decides whether the first run works |
| **~20 GB free disk** | Images (~33 GB unpruned), the Maven build cache, and 19 volumes |
| **BuildKit** (default in current Docker) | The Dockerfile uses cache mounts to keep the Maven repository out of the image. Without it, every rebuild re-downloads the world |
| JDK 21 + Maven | **Optional.** Only for running tests or attaching a debugger. Both services compile *inside* their image |

You do not need Java installed to run this lab. That is deliberate: a fresh clone with nothing but
Docker brings the whole system up.

```bash
./scripts/verify-toolchain.sh
```

**What you should see:** a report per prerequisite. A non-zero exit means a *build* prerequisite is
missing, which only matters if you intend to build outside Docker.

---

## 2. Start it

```bash
git clone <this repo> && cd observability-lab-parent
./scripts/infra.sh up
```

That one command creates `docker/compose/.env` from the tracked template, builds both service images,
starts everything, and waits until the stack converges.

**What you should see, in this order:**

```
Created docker/compose/.env from .env.example.

Starting the full stack: infrastructure, services, observability, simulation.
First run pulls several GB of images, compiles both services and initialises
Oracle; expect ten minutes or so. Subsequent runs are much faster.
...
  595s elapsed
Stack is ready.
```

followed by a health table and a list of URLs.

**How long.** First run ≈ 10 minutes: several GB of images, a full Maven build of both services, and
Oracle initialising a database from scratch. Later runs are 2–4 minutes.

**It is not stuck.** Oracle is the slow one and it is allowed up to about ten minutes to become
healthy. Watch it if you want proof it is working:

```bash
./scripts/infra.sh logs oracle | grep -i 'ready to use'
```

### Confirm it came up

```bash
./scripts/infra.sh health
```

**What you should see** — 35 lines. 23 `healthy`, 9 `running / none`, 3 `exited / exit 0`:

```
CONTAINER            STATE        HEALTH
lab-order-service    running      healthy
lab-inventory-service running     healthy
lab-oracle           running      healthy
lab-kafka-init       exited       exit 0
...
```

Three of them **should** be `exited`. `kafka-init`, `minio-init` and `consul-init` create topics,
buckets and configuration and then finish. `exit 0` is success; any other code is not, and
`infra.sh up` refuses to call the stack ready until all three exit cleanly.

Nine containers show `none` under HEALTH. That means *no healthcheck is declared*, not that they are
unwell — `fluent-bit`, `jaeger`, `tempo` and the five exporters are watched by Prometheus instead.

```bash
./scripts/infra.sh urls      # every UI, reading your actual .env
```

---

## 3. Prove the path works before you trust it

Four checks, each failing differently. Run them in order; failing at a known point is most of the
diagnosis.

```bash
# 1. The edge answers
curl -fsS http://localhost/healthz
```
**See:** `ok`

```bash
# 2. Identity works
TOKEN=$(./scripts/token.sh alice)
echo ${#TOKEN}
```
**See:** a number around `1179`. If this fails with a 503, Keycloak is still booting — wait 30 s.

**Always use `token.sh`.** A hand-written `curl` against `localhost:8080` mints a token whose issuer
is `localhost`, and the gateway only accepts `keycloak:8080`. That produces a 401 that looks like an
authorization bug and is a hostname. `token.sh` sends the right `Host` header for you.

```bash
# 3. The gateway authenticates
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/api/v1/orders
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" http://localhost/api/v1/orders
```
**See:** `401` then `200`. Both matter — the first proves the gateway is enforcing.

```bash
# 4. The whole path, with thresholds
./scripts/load.sh smoke
```
**See:** three green thresholds and 100% checks:

```
  █ THRESHOLDS
    checks            ✓ 'rate>0.99'      rate=100.00%
    http_req_duration ✓ 'p(95)<300'      p(95)=113.53ms
    http_req_failed   ✓ 'rate<0.001'     rate=0.00%
```

That one minute exercises Nginx, Kong, Keycloak, the Order Service, PostgreSQL, Redis, MinIO, Kafka,
the Inventory Service and Oracle. A green smoke run is the strongest single statement this lab makes
about itself.

---

## 4. Your first order, end to end

```bash
TOKEN=$(./scripts/token.sh alice)          # a USER
ADMIN=$(./scripts/token.sh manager)        # a USER *and* ADMIN

# Track a product. Creating stock is an operator action, so it needs the admin token.
curl -s -X POST http://localhost/api/v1/stock \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"productSku":"SKU-1","initialQuantity":100}'
```
**See:** `201`, and a body whose `data.availableQuantity` is `100`.

```bash
# Place an order
curl -s -X POST http://localhost/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"customerId":"C-1","currency":"EUR",
       "items":[{"productSku":"SKU-1","quantity":2,"unitPrice":10.50}]}'
```
**See:** `201`, and `"status":"PENDING"` with an order number like `ORD-20260722-A7938A48`.

> **`201` means accepted, not fulfilled.** The order is `PENDING` until the Inventory Service decides.
> That is the design: orders can be taken while Inventory is down.

```bash
# A few seconds later
ORDER=ORD-...                                    # paste yours
curl -s http://localhost/api/v1/orders/$ORDER -H "Authorization: Bearer $TOKEN"
```
**See:** `"status":"CONFIRMED"`. In a healthy stack this takes **about 4 seconds**.

What happened in between: the order and an outbox row were written in one transaction; a relay
published `order-created`; the Inventory Service reserved stock in Oracle and answered on
`inventory-updated`; the Order Service settled the order. That round trip is drawn span by span in
[docs/SequenceDiagrams.md §4](docs/SequenceDiagrams.md#4-settlement-through-kafka).

```bash
# The invoice — a short-lived signed URL into MinIO
curl -s http://localhost/api/v1/orders/$ORDER/invoice -H "Authorization: Bearer $TOKEN"
```
**See:** a `url` and an `expiresAt` 15 minutes out.

Every response carries `meta.requestId`, `meta.correlationId` and `meta.traceId`. The last one is what
you are about to use.

---

## 5. Watch it in Grafana

Open **<http://localhost:3000>**. You are signed in as a viewer automatically — anonymous viewing is
on, deliberately, so looking at a graph never requires a login. Editing needs `admin` and the password
in `.env`.

Eleven dashboards, in five folders:

| Dashboard | UID | Open it when |
| --- | --- | --- |
| Platform — Overview | `lab-platform-overview` | You want one screen for the whole system |
| HTTP — Requests | `lab-http` | Rate, errors, duration per endpoint |
| Business — Order Flow | `lab-business-metrics` | Orders accepted, settled, rejected |
| Databases — Connection Pools | `lab-databases` | **The first place to look when latency rises** |
| JVM — Memory, GC, Threads | `lab-jvm` | Heap, GC pause, thread pools |
| Kafka — Producers, Consumers, Lag | `lab-kafka` | Consumer lag, DLQ |
| Redis — Cache and Commands | `lab-redis` | Hit ratio, evictions |
| gRPC — Service Communication | `lab-grpc` | Deadlines, statuses, circuit breaker |
| Traces — Span Metrics and Service Graph | `lab-traces` | Service topology from real spans |
| Profiles — CPU, Allocation, Locks | `lab-profiles` | Flame graphs |
| Alerting — Firing, Routing and Delivery | `lab-alerting` | What fired, and whether it was delivered |

Direct link: `http://localhost:3000/d/lab-business-metrics`.

### Find the order you just placed

**In Grafana → Explore → Loki**, paste your order number as a line filter:

```logql
{service="order-service"} |= "ORD-20260722-A7938A48"
```

**See:** the lines that order produced — `Order accepted with 1 line(s)…`, then the settlement. Each
carries `trace_id`.

> **Why a line filter and not a label?** Loki indexes *labels*, and the only labels here are
> `service`, `job`, `level`, `pipeline`, `environment`, `filename` and `service_name` — all
> low-cardinality on purpose. Making `order_number` a label would create one stream per order and take
> Loki down with it. See [docs/Logging.md](docs/Logging.md).

### Pivot from that log line to the trace

Copy the `trace_id` from any of those lines and open **Explore → Tempo → Search by TraceID**.

**See:** one trace, around **47 spans**, spanning both services — including the Oracle statements the
Inventory Service ran:

```
inventory-service   SELECT freepdb1.processed_events           1.02ms
inventory-service   SELECT freepdb1.stock_levels               1.08ms
inventory-service   UPDATE freepdb1.stock_levels               0.89ms
inventory-service   INSERT freepdb1.stock_movements            0.79ms
inventory-service   INSERT freepdb1.processed_events           0.67ms
```

That first `SELECT` on `processed_events` is the idempotency check — the reason a redelivered event
does not decrement stock twice.

You can also search Tempo by business attribute, which is why those attributes are set by hand:

```traceql
{span.order.number="ORD-20260722-A7938A48"}
```

**One caveat, and it will confuse you once:** a trace is fetchable **by ID immediately**, but
searchable **by attribute only after Tempo flushes** — a minute or two. A search that returns nothing
right after placing an order is usually a flush delay, not a missing trace.

---

## 6. The three things most likely to go wrong

### 1. Oracle does not become healthy — and everything blames something else

**Looks like:** `up` runs for ten minutes and times out. `lab-inventory-service` never becomes
healthy. `lab-oracle` sits at `starting`.

**Almost always memory.** Oracle is capped at 2560M and needs most of it.

```bash
docker inspect --format '{{.State.Health.Status}}' lab-oracle
./scripts/infra.sh logs oracle | grep -i 'ready to use\|ORA-'
```

**Fix:** raise Docker's memory allocation to 10 GB and run `./scripts/infra.sh up` again. Below ~8 GB
Oracle will not start, and the Inventory Service cannot become ready until it has.

**Do not** shorten the healthcheck because it looks stuck. A first initialisation genuinely takes
minutes.

### 2. A stale `.env` — the stack starts and misbehaves, or refuses to start at all

You only hit this if you have run an older version of this repo. `infra.sh` never overwrites an
existing `.env`, so one written several steps ago is missing keys added since.

**Looks like** one of two things, depending on which keys are missing:

```
# missing keys feed resource limits -> nothing starts
'services[order-service].deploy.resources.limits.cpus' strconv.ParseFloat: parsing "": invalid syntax

# missing keys feed ports or env vars -> the stack starts and misbehaves
WARNING: docker/compose/.env is missing 27 key(s) that .env.example defines:
```

**The nastier variant has no warning at all.** Two keys changed *meaning* rather than disappearing:
`REDIS_PORT` is now the in-network Toxiproxy listener (`16379`, published one moved to
`REDIS_HOST_PORT`), and `KEYCLOAK_ISSUER` moved from `localhost:8080` to `keycloak:8080`. A `.env`
carrying the old values passes the check and then 401s every request.

**Fix — back it up and regenerate, then re-apply your own overrides:**

```bash
cd docker/compose
cp .env ".env.stale-$(date +%Y%m%d-%H%M%S)"
cp .env.example .env
# re-apply local overrides; a remapped published port is the usual one
```

### 3. A port on your machine is already taken

**Looks like:** `bind: address already in use` during `up`.

| Port | Usually |
| --- | --- |
| **80** | On Windows, IIS or the kernel HTTP stack. The most common casualty |
| 5432 | A natively installed PostgreSQL |
| 6379 | A natively installed Redis |
| 8080 | Another Keycloak, or any JVM app |

**Fix:** change the variable in `docker/compose/.env` — never in a compose file, so your override
stays machine-local. Moving the lab is almost always better than stopping whatever owns the port.

```bash
# in docker/compose/.env
NGINX_HTTP_PORT=8888
```

This is safe because **a published port is only for you**. Nothing inside the stack uses one; every
component addresses every other by its compose name on `lab-net`. Changing a published port can
inconvenience a human and cannot break the system.

`./scripts/infra.sh urls` always prints the effective addresses.

### On Windows, one extra thing

Git Bash rewrites arguments that look like absolute POSIX paths, so a hand-written `docker exec` fails
with a path prefixed `C:/Program Files/Git/`:

```
exec: "C:/Program Files/Git/opt/kafka/bin/kafka-topics.sh": no such file or directory
```

The repo's scripts already set `MSYS_NO_PATHCONV=1`. For your own commands:

```bash
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
```

---

## 7. Break it on purpose

The lab exists for this. Two levels, one command each, and one command that undoes everything.

```bash
./scripts/chaos.sh list                    # what is injected right now
./scripts/chaos.sh slow postgres 400       # +400 ms on every database response
./scripts/chaos.sh reset                   # undo everything, both levels
```

**Run `./scripts/chaos.sh list` before you measure anything.** A toxic left over from an earlier
experiment produces a system that looks broken in exactly the way a regression looks broken, and no
signal anywhere says a human did it on purpose.

Try one now, with the Databases dashboard open:

```bash
./scripts/load.sh load &                   # 10 orders/s for 5 minutes
sleep 60 && ./scripts/chaos.sh slow postgres 400
```

**See, within about a minute:** p99 latency climbing from ~140 ms to several seconds,
`hikaricp_connections_pending` rising off zero, and — the interesting part — `pg_up` still reporting
**1**, because the exporters reach the database directly and never through the fault proxy.

That disagreement is the diagnosis: the fault is in the *path*, not the datastore. This exact
experiment is worked through end to end, with the numbers, in
[docs/Debugging.md §3](docs/Debugging.md#31-investigation-1--the-checkout-page-got-slow).

Then:

```bash
./scripts/chaos.sh reset
```

---

## 8. Where to go next

| You want to | Read |
| --- | --- |
| Run it day to day | [docs/Operations.md](docs/Operations.md) |
| Know which knob does what | [docs/Configuration.md](docs/Configuration.md) |
| Find the cause of something | [docs/Debugging.md](docs/Debugging.md) — the centrepiece |
| Practise, and check your answers | [docs/Exercises.md](docs/Exercises.md) |
| Understand the design | [docs/Architecture.md](docs/Architecture.md), [docs/SequenceDiagrams.md](docs/SequenceDiagrams.md) |
| Break it methodically | [docs/Simulation.md](docs/Simulation.md), [docs/FailureSimulation.md](docs/FailureSimulation.md) |

---

## 9. What this lab is not

Worth reading before you copy anything from it into something real.

- **No TLS anywhere.** Every hop is plain HTTP, including between services. It is survivable only
  because every port binds to `127.0.0.1`.
- **Credentials are placeholders in a git-ignored `.env`.** No secret manager, no rotation, no audit.
- **Single node everything** — one Kafka broker with replication factor 1, one Consul server, one
  replica per service. A redeploy is an outage.
- **One flat network.** Four tier networks were deliberately collapsed into one;
  [docs/InfrastructureDiagram.md §9](docs/InfrastructureDiagram.md#9-the-one-network-and-what-it-cost)
  records what that gave up.
- **No backups, and no down-migrations.** The rollback for a bad migration is `destroy`.
- **Chaos endpoints are compiled into the services.** They are guarded three ways and absent under
  `prod`, but they would not exist at all in a real artefact.
- **100% trace sampling and always-on profiling.** Correct for a lab, wrong for production — and it
  means every latency number here includes the cost of watching it.

The full list, control by control, is in [docs/Security.md §12](docs/Security.md#12-what-is-deliberately-not-secured)
and [docs/Deployment.md §12](docs/Deployment.md#12-what-this-deployment-is-not).
