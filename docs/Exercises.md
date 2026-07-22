# Exercises

Graded, hands-on, against the running stack. Each one poses a **question**, not a procedure. The
answer is a number, a query or a diagnosis — something you can check.

> **How to use this.** Solutions are in §8, deliberately far from the questions. Try first; a wrong
> answer you had to abandon teaches more than a right one you read.
>
> Order matters. The exercises build: you cannot correlate a trace to a log before you can read
> either. Do them in sequence the first time.
>
> **Reset before every exercise that measures anything:**
> ```bash
> ./scripts/chaos.sh reset && ./scripts/infra.sh health
> ```

**Prerequisite:** a converged stack. If `./scripts/infra.sh health` does not show 35 containers, start
with [GETTING_STARTED.md](../GETTING_STARTED.md).

---

## Level 1 — Read the system as it is

No faults. You are learning where things are.

### 1.1 — The container census

Bring the stack up and count.

**a)** How many containers are running or exited after a default `up`?
**b)** How many report no health status at all, and why is that not a problem?
**c)** Three containers are `exited`. Why is that success rather than failure, and what would `exit 1`
from one of them mean for the rest of the stack?

*Checkable: three numbers and one sentence.*

### 1.2 — Which port is the real one?

The Order Service connects to PostgreSQL.

**a)** What host and port does it actually use? Find it from the running container, not from a
document.
**b)** `POSTGRES_PORT=5432` is in `.env`. What is that number for?
**c)** If you changed `POSTGRES_PORT` to `25432` and restarted, what inside the stack would break?

*Checkable: an address, a purpose, and a one-word answer to (c).*

<details><summary>Hint</summary>

`docker exec lab-order-service env | grep -i db`
</details>

### 1.3 — Partitions and threads

**a)** How many partitions does `order-created` have?
**b)** How many consumer threads does the Inventory Service run against it?
**c)** You add a second Inventory Service instance. How many of its listener threads will receive
records?

*Checkable: three numbers.*

### 1.4 — The retention trap

You are asked to compare today's latency against the same load test run nine days ago.

**a)** Can you do it in Prometheus? What is its configured retention?
**b)** Where else could the data be, and for how long?
**c)** Does the query have to change?

*Checkable: two durations and a yes/no.*

---

## Level 2 — Read one signal

### 2.1 — A smoke run, read properly

```bash
./scripts/chaos.sh reset && ./scripts/load.sh smoke
```

**a)** What are the three thresholds, and what is the p95?
**b)** `http_reqs` is larger than `iterations`. Why?
**c)** The scenario uses a fixed *arrival rate* rather than a fixed number of virtual users. What would
a fixed-VU test have hidden?

*Checkable: three numbers and one explanation.*

### 2.2 — Find your own order in the logs

Place an order through the edge, then find it.

**a)** Write the LogQL that finds only that order's lines.
**b)** How many distinct log lines did that one order produce in the Order Service?
**c)** Which field in those lines will take you to the trace?

*Checkable: a query, a count, a field name.*

### 2.3 — Why is `order_number` not a label?

**a)** List every label Loki actually has.
**b)** Explain, in one sentence, what would happen if `order_number` were promoted to a label.
**c)** What is the operator you must use instead?

*Checkable: a list of 7, one sentence, one operator.*

---

## Level 3 — Correlate signals

Depends on Level 2: you must be able to read a log line and a trace before joining them.

### 3.1 — Log to trace

Starting **only** from an order number a customer gave you:

**a)** Extract the `trace_id` without opening a UI.
**b)** Fetch that trace and count its spans.
**c)** Which service contributes the most spans — and is that the same as the service where the time
went? Answer both parts separately.

*Checkable: a trace id, a span count, a service name, and a yes/no with a reason.*

### 3.2 — Read the settlement

In the trace from 3.1, find the Inventory Service's Oracle statements.

**a)** What is the **first** statement it runs, before touching stock?
**b)** Why is it there? What breaks without it?
**c)** Which document describes this as a designed property rather than an implementation detail?

*Checkable: a table name, a failure mode, a document reference.*

### 3.3 — The search that returns nothing

Place an order and immediately run:

```traceql
{span.order.number="ORD-…"}
```

**a)** How many traces come back?
**b)** Run it again two minutes later. How many now?
**c)** What does that tell you about when to trust an empty Tempo search?

*Checkable: two counts and a rule of thumb.*

---

## Level 4 — Break it, and read what happens

Each of these is one command. The expected signals are written down **before** the run in
[FailureSimulation.md](FailureSimulation.md) and [Simulation.md](Simulation.md) — read the expectation
first, then check it.

### 4.1 — The slow dependency

```bash
./scripts/load.sh load &
sleep 60 && ./scripts/chaos.sh slow postgres 400
```

**a)** Which metric moves **first** — latency, or something else?
**b)** What does `pg_up` report during the fault, and what does that disagreement mean?
**c)** Which alert fires first, and why is it not `HighHttpLatency`?
**d)** Quote the log line that names the cause exactly.

*Checkable: a metric name, a value, an alert name, a log message.*

### 4.2 — The arithmetic of a slow dependency

Same fault. Before running it, **predict**:

**a)** With a 10-connection pool and roughly 50 ms of database work per request, what arrival rate can
the system sustain?
**b)** Add 400 ms to every database call. Now what rate can it sustain?
**c)** The offered load stays at 10/s. What happens to the difference?

*Checkable: two rates and one word.*

### 4.3 — The order that never settles

```bash
./scripts/chaos.sh down kafka
# place an order through the edge
```

**a)** Does the API still return `201`? Should it?
**b)** After 20 seconds, what is the order's status?
**c)** Where is the event? Give the SQL that proves it, and the metric that reports the same fact.
**d)** Heal it. How long until the order settles, and what did you have to do by hand?

*Checkable: a status code, an order status, one SQL query, one metric name, a duration.*

### 4.4 — The health check that lies

```bash
./scripts/chaos.sh down inventory-http
./scripts/chaos.sh down inventory-grpc
```

**a)** What does Kong report for the `inventory-service.upstream` health?
**b)** Is Kong wrong? Explain in one sentence.
**c)** Place an order. Does it still reach `CONFIRMED`? Why?
**d)** Which metric *would* have shown the fault?

*Checkable: a health status, one sentence, a status, a metric name.*

### 4.5 — The fix that looks like it failed

Run 4.1, then heal it and watch.

**a)** Which metric returns to normal immediately?
**b)** How long does `lab:http_latency_p99:5m` stay elevated after the fault is gone, and why?
**c)** Write a query that shows the recovery five times sooner.
**d)** What would have happened if somebody had rolled back at minute two?

*Checkable: a metric, a duration, a PromQL expression.*

---

## Level 5 — Capacity

### 5.1 — Find the ceiling yourself

Do not read [Performance.md](Performance.md) first.

```bash
./scripts/chaos.sh reset
RATE=10 ./scripts/load.sh load
RATE=20 ./scripts/load.sh load
```

**a)** At which rate do errors appear?
**b)** Quote the error verbatim. What resource ran out?
**c)** Is CPU saturated at that point? What does that tell you about which limit binds?

*Checkable: a rate, an error string, yes/no.*

### 5.2 — Move the ceiling

Raise `maximum-pool-size` from 10 to 20 in `services/order-service/src/main/resources/application.yml`,
rebuild, and re-measure.

**a)** Does the ceiling move? To roughly what?
**b)** What binds next?
**c)** Name one thing this experiment invalidates.

*Checkable: a rate, a resource, one sentence.*

### 5.3 — Measuring the observer

**a)** Name four things in this stack that add cost to every request purely to observe it.
**b)** Design an experiment that measures their combined cost. What exactly would you change?
**c)** Why is that number not a reason to remove them here?

*Checkable: a list of four, a one-line config change, one sentence.*

---

## Level 6 — Judgement

No commands. These are the ones an interviewer asks.

### 6.1 — Alert triage

Read the 33 rules in `infrastructure/prometheus/rules/`.

**a)** Which would you accept being woken at 03:00 for, and which must never page?
**b)** One fault in 4.1 put five alerts into `pending` or `firing`. What mechanism stops that from
becoming five pages?
**c)** Name an alert in this repo that could be firing right now and be **unable** to tell you
anything. What makes it useless?

*Checkable: a shortlist, a mechanism name, an alert name.*

### 6.2 — What must not be copied

**a)** Name five things in this lab that would be defects in production.
**b)** For each, say what the production version is.
**c)** Which one is the most dangerous to copy *accidentally* — because it looks like a normal design
decision rather than a simplification?

*Checkable: five pairs, and one argued choice.*

### 6.3 — The gap that is not written down

**a)** Find one claim in this repository's documentation that the running stack contradicts.
**b)** Prove it with a command.
**c)** Decide: is the document wrong, or is the configuration wrong?

*Checkable: a citation, a command, an argued answer. There is at least one; §8 names it.*

---

## 7. Where the answers come from

| Level | Reference |
| --- | --- |
| 1 | [InfrastructureDiagram.md](InfrastructureDiagram.md), [Configuration.md](Configuration.md) |
| 2 | [Logging.md](Logging.md), [Metrics.md](Metrics.md), [Performance.md §6](Performance.md#6-how-to-measure) |
| 3 | [Tracing.md](Tracing.md), [Debugging.md §4](Debugging.md#4-pivoting-between-signals) |
| 4 | [Simulation.md](Simulation.md), [FailureSimulation.md](FailureSimulation.md), [Debugging.md §3](Debugging.md#3-four-investigations) |
| 5 | [Performance.md](Performance.md) |
| 6 | [Alerting.md](Alerting.md), [Security.md §12](Security.md#12-what-is-deliberately-not-secured), [Deployment.md §12](Deployment.md#12-what-this-deployment-is-not) |

---

## 8. Solutions

Stop here if you have not tried.

<details>
<summary><b>Level 1</b></summary>

**1.1** (a) **35** — 32 running plus 3 exited. 41 are *declared*; 5 sit behind the `search` profile and
1 behind `load`. (b) **9** have no healthcheck; they are watched by Prometheus instead
(`up{job="fluent-bit"}` and friends). `fluent-bit` has none deliberately: `fluent-bit --version` would
pass while the process shipped nothing, reporting healthy through a total outage. (c) `kafka-init`,
`minio-init` and `consul-init` create topics, buckets and KV then finish; `exit 0` is success.
`infra.sh up` treats any other code as *not ready* — otherwise a failed bucket setup passes for a
healthy stack and surfaces much later, somewhere else.

**1.2** (a) `toxiproxy:15432` — `docker exec lab-order-service env | grep ORDER_DB`. (b) The *host*
publication, so you can run `psql` from your shell. (c) **Nothing.** A published port exists for a
person; nothing inside the stack uses one.

**1.3** (a) **3** — from `KAFKA_TOPIC_PARTITIONS=3`. The `7` in `create-topics.sh` is *retention days*,
not partitions. (b) **3** (`spring.kafka.listener.concurrency`) — one per partition. (c) **Zero.**
Partitions are the cap; a second instance's threads sit idle until you add partitions.

**1.4** (a) **No.** Prometheus is started with `--storage.tsdb.retention.time=24h` — one day. (b)
VictoriaMetrics, **30 days**, which receives a `remote_write` copy of every sample. (c) **No** — it
answers the same PromQL. Only the datasource changes.
</details>

<details>
<summary><b>Level 2</b></summary>

**2.1** (a) `checks rate>0.99`, `http_req_duration p(95)<300`, `http_req_failed rate<0.001`; measured
p95 ≈ **113 ms**. (b) Each iteration makes several requests — it seeds stock, places an order and reads
it back. (c) With fixed VUs each user waits for its response before sending the next request, so a
system that slows down *receives less traffic* and hides its own degradation behind flat throughput.

**2.2** (a) `{service="order-service"} |= "ORD-…"`. (b) **8** on a healthy stack at default log levels
— acceptance, the outbox enqueue, the invoice upload, the relay publishing, the settlement, and
related `DEBUG` lines. Not 2 or 3: one order touches more of the system than it looks like it should.
(c) **`trace_id`**.

**2.3** (a) `environment`, `filename`, `job`, `level`, `pipeline`, `service`, `service_name`. (b) One
stream per order number — unbounded cardinality, and Loki falls over. (c) The line filter `|=`, or
`| json` to parse fields out of the record.
</details>

<details>
<summary><b>Level 3</b></summary>

**3.1** (a)
```bash
curl -s -G http://localhost:3100/loki/api/v1/query_range \
  --data-urlencode 'query={service="order-service"} |= "ORD-…" | json | line_format "{{.trace_id}}"' \
  --data-urlencode "start=$(( ($(date +%s) - 600) ))000000000" \
  --data-urlencode "end=$(date +%s)000000000" --data-urlencode 'limit=5'
```
(b) `curl -s http://localhost:3200/api/traces/<id>` → **47 spans** on a healthy stack. (c) Measured:
**order-service 28, inventory-service 19**. So the *span count* leader is the Order Service — it owns
the HTTP entry, the transaction, the outbox write, the invoice upload and the Kafka produce.

But span count is not time. The Inventory Service's 19 spans are where the Oracle work happens, and
each is sub-millisecond. **This is the trap in the question:** counting spans tells you which service
is chattiest, not which one is slow. For "where did the time go", read span *durations* on the
waterfall, not the census.

**3.2** (a) `SELECT freepdb1.processed_events`. (b) The idempotency ledger. Kafka is at-least-once, so
a redelivery is normal; without this check a redelivered `order-created` would decrement stock twice.
(c) [SequenceDiagrams.md §4](SequenceDiagrams.md#4-settlement-through-kafka) and
[Kafka.md](Kafka.md).

**3.3** (a) **0.** (b) **1–2.** (c) A trace is fetchable **by ID immediately** but searchable **by
attribute only after Tempo flushes**. An empty search seconds after the event is flush lag, not a
missing trace — never conclude "no trace" from a fresh search.
</details>

<details>
<summary><b>Level 4</b></summary>

**4.1** (a) **`hikaricp_connections_pending`** — it moves before latency, because queueing is what
*causes* the latency. Measured 0 → 32 → 42 while p99 went 143 ms → 6699 ms → 8417 ms. (b) `pg_up = 1`
throughout. The exporters reach PostgreSQL **directly** while the service goes through Toxiproxy, so
two views of one database disagree — which locates the fault **in the path**, not in the database.
(c) **`DatabasePoolExhausted`** (`for: 2m`). `HighHttpLatency` waits 10 m, so it is still `pending`
while the cause is already firing — the alert nearest the cause arrives first, and inhibition
suppresses the consequences. (d) `order-db-pool - Connection is not available, request timed out after
3000ms (total=10, active=10, idle=0, waiting=134)`.

**4.2** (a) `10 / 0.05` = **~200/s**. (b) `10 / 0.45` = **~22/s**. (c) It **queues** — and the queue is
what the user experiences as a 5.7 s p95.

**4.3** (a) **Yes**, `201`, and it should: the API is deliberately decoupled from settlement, which is
what lets orders be taken while Inventory or the broker is down. (b) `PENDING`. (c) It is in the
outbox, unpublished:
```sql
SELECT count(*) FILTER (WHERE published_at IS NULL) AS pending,
       coalesce(max(attempts),0) FROM outbox_events;   -- 1|1
```
There is no `status` column; `published_at IS NULL` *is* pending. The metric is
`lab_outbox_pending_events` (= 1), which `OutboxBacklogGrowing` alerts on above 50 for 3 m. (d)
**≈25 s**, and **nothing by hand** — the relay polls every second and publishes it.

**4.4** (a) **HEALTHY**, during the fault. (b) Kong is not wrong: it reaches Inventory **directly**
while the Order Service reaches it **through Toxiproxy**, so Kong's path is genuinely fine and it
simply cannot see the broken one. (c) **Yes, `CONFIRMED`.** The gRPC express reservation missed its
200 ms deadline and the Kafka path settled the order — the fallback working as designed. (d)
`grpc_client_channel_state{state="TRANSIENT_FAILURE"}` or
`resilience4j_circuitbreaker_state{state="open"}` — the signal lives on the **client**.

**4.5** (a) `hikaricp_connections_pending`, immediately to 0. (b) **Five minutes** — the recording rule
is a 5 m window and still contains the slow requests. Measured 9172 ms at +45 s, 9180 ms at +90 s,
20.2 ms once it rolled. (c)
```promql
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{service="order-service"}[1m])))
```
(d) A rollback triggered by arithmetic rather than by a fault.
</details>

<details>
<summary><b>Level 5</b></summary>

**5.1** (a) Clean at 10/s; **20/s** returns ~28% errors. (b) `order-db-pool - Connection is not
available, request timed out after 3000ms` — the **connection pool**, size 10. (c) **No**, CPU is not
saturated. Idle CPU beside a saturated pool is the normal shape of this failure, and it says the pool
binds first.

**5.2** (a) Yes, roughly double before symptoms return. (b) Usually **Tomcat threads** or the database
itself — its own `max_connections` now takes the contention. (c) Every k6 default: `RATE=10` was
calibrated against a 10-connection pool, so it is no longer "the rate that runs clean", and no number
in [Performance.md §2](Performance.md#2-the-measured-ceiling) is comparable any more.

**5.3** (a) The OpenTelemetry agent at 100% sampling; the Pyroscope agent (`itimer` + alloc + lock);
Micrometer histograms with SLO buckets; JSON logging to file *and* stdout under `dev`. Toxiproxy in
every dependency path is a fifth. (b) Drop the two `-javaagent:` lines from `JAVA_TOOL_OPTIONS` in
`docker-compose.services.yml`, rebuild, re-run the identical scenario, compare p95. (c) Because
removing them removes the thing being taught — but the delta is worth knowing, and it is why a latency
number here always includes the cost of watching it.
</details>

<details>
<summary><b>Level 6</b></summary>

**6.1** (a) Page-worthy: `ServiceDown`, `PostgresDown`, `OracleDown`, `KafkaBrokerDown`,
`OrdersAcceptedButNotSettled`, `DiskSpaceCritical` — user-visible or imminently so. Never page:
`ServiceRestarted`, `UnusualTrafficVolume`, `KafkaConsumerLagGrowing` — all `info`, all context. A
`404`, a rejected order and an insufficient-stock refusal are correct answers to bad requests and must
never alert at all. (b) **Grouping and inhibition** in Alertmanager: a critical suppresses the warnings
it causes, so one root failure is one notification. (c) Any alert whose exporter is down —
`PostgresSlowQueries` cannot fire if `postgres-exporter` is not scraped. `ExporterDown` exists to make
that visible, because a silent alert is indistinguishable from a healthy one.

**6.2** (a) and (b): no TLS anywhere → TLS at the edge plus mTLS or a mesh; credentials in a
git-ignored `.env` → a secret manager with rotation and audit; single-node Kafka at RF 1 → three-plus
brokers, RF ≥ 3, `min.insync.replicas` ≥ 2; one replica per service → several behind a balancer so a
deploy is not an outage; no backups and no down-migrations → tested restores and a reversible
migration strategy; Consul ACLs disabled → `default_policy = deny` with per-service tokens; chaos
endpoints compiled in → absent from the artefact. (c) Arguably **`policy: local` on Kong's rate
limiter**: it looks like an ordinary configuration choice and is correct only because there is exactly
one Kong node. Copy it to a cluster and every node allows the full quota, so the limit silently becomes
*N* times what it says. The single flat network is a close second, because a diagram of it looks
segmented.

**6.3** One known example: [SystemDesign.md §11](SystemDesign.md#11-non-functional-targets) states
metric retention as 15 days (Prometheus) and 90 days (VictoriaMetrics).
```bash
curl -s http://localhost:9090/api/v1/status/flags | grep retention
#   "storage.tsdb.retention.time": "1d"
grep retentionPeriod docker/compose/docker-compose.observability.yml
#   --retentionPeriod=30d
```
Both are wrong by a wide margin. **Neither is simply "wrong":** §11 is a *targets* table and the
compose flags are what a laptop was given — so the honest resolution is to record both, which is what
[Configuration.md §5](Configuration.md#5-retention-ttls-and-sampling--and-what-each-costs) now does.
Deciding which to change is a capacity question, not a documentation one.
</details>

---

## 9. Related

| Document | For |
| --- | --- |
| [Debugging.md](Debugging.md) | The worked method behind Level 3 and 4 |
| [Simulation.md](Simulation.md) | Network faults and load, signal by signal |
| [FailureSimulation.md](FailureSimulation.md) | The 13 in-process scenarios, expectations written first |
| [Performance.md](Performance.md) | The measured ceiling behind Level 5 |
| [Operations.md](Operations.md) | Running the stack the exercises need |
| [GETTING_STARTED.md](../GETTING_STARTED.md) | If the stack is not up |
