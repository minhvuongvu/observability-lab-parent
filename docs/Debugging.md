# Debugging

Finding the cause. Four investigations worked start to finish, how to move between the four signals,
and what each tool is bad at.

> **Every number in this document was measured**, on this stack, while it was being written. The
> investigations are not illustrative — they are transcripts, trimmed.
>
> Each one starts from **something a person said**, not from a metric. That is the hard part: a metric
> tells you where to look next, but nobody opens a dashboard until somebody complains.

---

## 1. Before anything else

```bash
./scripts/chaos.sh list && ./scripts/chaos.sh app status
```

**If this is a lab machine, this is the first command, every time.** A toxic left over from an earlier
experiment produces a system that is broken in exactly the way a regression is broken, and no signal
anywhere says a human did it on purpose. More debugging time is lost here than to any real fault.

Then the four questions:

| # | Question | Command |
| --- | --- | --- |
| 1 | Is a fault injected? | `./scripts/chaos.sh list` |
| 2 | Is every container converged? | `./scripts/infra.sh health` |
| 3 | Is any scrape target down? | `curl -s 'http://localhost:9090/api/v1/query?query=up==0'` |
| 4 | Is this the path or the component? | Compare the service's view against the exporter's |

Question 4 is this stack's signature move, and §3.1 is what it looks like.

---

## 2. Symptom → signal → tool → query

| Symptom | Start with | Tool | Query |
| --- | --- | --- | --- |
| Requests slow | Metrics | Prometheus | `lab:http_latency_p99:5m` |
| Requests slow, CPU idle | Metrics | Prometheus | `hikaricp_connections_pending` |
| Requests slow, pool fine | Metrics | Prometheus | `tomcat_threads_busy` |
| Requests failing | Metrics → Traces | Prometheus → Tempo | `rate(http_server_requests_seconds_count{outcome!="SUCCESS"}[5m])` |
| One order misbehaving | Logs | Loki | `{service="order-service"} \|= "ORD-…"` |
| Order stuck `PENDING` | Database + Kafka | psql, Kafka CLI | outbox pending, then consumer lag |
| Where did the time go? | Traces | Tempo | `{span.order.number="ORD-…"}` |
| Which method burned it? | Profiles | Pyroscope | CPU flame graph, filtered by span |
| Memory climbing | Profiles | Pyroscope | **live heap**, not allocation |
| Something is down | Metrics | Prometheus | `up == 0` |
| Nothing is arriving | Metrics | Prometheus | `up{job="fluent-bit"}`, `rate(...[5m]) == 0` |

The full symptom map, per signal, is [Observability.md §5](Observability.md#5-where-to-look-by-symptom).

---

## 3. Four investigations

### 3.1 Investigation 1 — "The checkout page got slow"

**Reported:** orders are taking seconds instead of being instant. Nothing is failing outright, at
first.

#### Step 1 — confirm it, and bound it

```bash
curl -s http://localhost:9090/api/v1/query \
  --data-urlencode 'query=lab:http_latency_p99:5m{service="order-service"}'
```

| When | p99 |
| --- | --- |
| Baseline | **143.7 ms** |
| t+60 s | **6698.9 ms** |
| t+120 s | **8416.6 ms** |

Real, and getting worse. So: is the service busy, or is it waiting?

#### Step 2 — the pool, before the CPU

```bash
curl -s http://localhost:9090/api/v1/query --data-urlencode 'query=hikaricp_connections_pending'
curl -s http://localhost:9090/api/v1/query --data-urlencode 'query=hikaricp_connections_active'
```

| When | active | pending |
| --- | --- | --- |
| Baseline | 0 | 0 |
| t+60 s | 9 | **32** |
| t+120 s | **10** (of 10) | **42** |

The pool is fully consumed and 42 threads are queued for a connection. **`hikaricp_connections_pending`
is the leading indicator in this system** — it moves before latency does, because queueing is what
*causes* the latency.

At this point the answer is "the database is slow". It is also wrong.

#### Step 3 — the wrong turn

> **The database is slow, so look at the database.**

```bash
curl -s http://localhost:9090/api/v1/query --data-urlencode 'query=pg_up'
```

```
  pg_up = 1
```

PostgreSQL is up. `PostgresDown` has not fired. `pg_stat_activity` looks unremarkable. Spend fifteen
minutes here and you will conclude the metrics are lying.

**They are not, and the disagreement *is* the answer.** `postgres-exporter` reaches PostgreSQL
**directly**; the Order Service reaches it **through Toxiproxy**. Two components report on the same
database and disagree, so the fault is in **the path between them**, not in the database.

That asymmetry is deliberate, and it is drawn in
[InfrastructureDiagram.md §4](InfrastructureDiagram.md#4-the-fault-injection-topology).

#### Step 4 — the log line that names it

```logql
{service="order-service"} |= "Connection is not available"
```

```json
{"level":"ERROR","logger":"org.hibernate.engine.jdbc.spi.SqlExceptionHelper",
 "message":"order-db-pool - Connection is not available, request timed out after 3000ms
            (total=10, active=10, idle=0, waiting=134)"}
```

`total=10, active=10, idle=0` — the pool, exhausted, with its own diagnosis attached.

#### Step 5 — which alert fired, and in what order

```bash
curl -s http://localhost:9090/api/v1/query --data-urlencode 'query=ALERTS'
```

```
  DatabasePoolExhausted        firing    warning
  HighHttpLatency              pending   warning
  HighHttpFaultRate            pending   critical
  OrdersAcceptedButNotSettled  pending   critical
  KafkaConsumerLagGrowing      pending   info
```

**The pool alert fires first** — `for: 2m` — while the four *consequences* are still `pending` behind
longer windows. That ordering is the design working: the alert that arrives first is the one nearest
the cause. Inhibition then suppresses the rest.

#### The finding

400 ms of injected latency on the database path. Not a code change, not a query plan.

```bash
./scripts/chaos.sh list      # would have said so in step 0
./scripts/chaos.sh heal postgres
```

#### What it teaches

- The pool is this system's binding constraint, and `pending` leads latency.
- **Two components disagreeing about one dependency locates the fault between them.**
- 400 ms is nothing on an idle system and fatal on a saturated one: a 10-connection pool with 50 ms of
  work sustains ~200/s; add 400 ms and it sustains ~22/s. The offered load did not change, so
  everything above 22/s queued.

---

### 3.2 Investigation 2 — "I placed an order and it just says PENDING"

**Reported:** one customer, one order, stuck. The API returned `201`, so the order exists.

#### Step 1 — is `PENDING` even wrong?

No, not immediately. `201` means **accepted, not fulfilled**; the order is `PENDING` until the
Inventory Service decides. In a healthy stack that takes about **4 seconds**.

Twenty seconds is wrong. Sixty is definitely wrong.

#### Step 2 — the wrong turn

> **The order service returned it, so look at the order service.**

```bash
curl -s http://localhost:9090/api/v1/query \
  --data-urlencode 'query=lab:http_latency_p99:5m{service="order-service"}'
curl -s 'http://localhost:9090/api/v1/query?query=up==0'
```

p99 is normal. No errors. Everything is `up`. The HTTP dashboard is entirely green — **because the
settlement path is not HTTP.** The synchronous half did its job perfectly; the asynchronous half is
where the order lives now.

This is the decoupling working exactly as intended, and it is why the symptom is invisible to the
request metrics.

#### Step 3 — walk the settlement path, cheapest check first

The path is: outbox row → `order-created` → Inventory reserves → `inventory-updated` → settle.

```bash
docker exec -i lab-postgres psql -U postgres -d orderdb -tAc \
  "SELECT count(*) FILTER (WHERE published_at IS NULL) AS pending,
          coalesce(max(attempts),0) FROM outbox_events;"
```

```
  1|1
```

**One row unpublished, one delivery attempt made.** The event never left the Order Service. There is
no `status` column — `published_at IS NULL` *is* pending.

The same fact as a metric, which is what the alert uses:

```bash
curl -s http://localhost:9090/api/v1/query --data-urlencode 'query=lab_outbox_pending_events'
```

```
  lab_outbox_pending_events = 1
```

`OutboxBacklogGrowing` fires above 50 for 3 minutes; a single stuck row is below the threshold, which
is correct — one row is a hiccup, fifty is an outage.

#### Step 4 — why could it not publish?

```bash
./scripts/chaos.sh list
```

The `kafka` proxy is refusing connections. The relay tried, failed, and left the row for the next
poll — which is precisely what an outbox is for.

#### Step 5 — heal, and watch it fix itself

```bash
./scripts/chaos.sh reset
```

25 seconds later:

```
  order status              -> CONFIRMED
  outbox pending after heal -> 0
```

**Nothing was replayed by hand.** The relay polls every second, found the row, published it, and the
round trip completed.

#### What it teaches

- A green HTTP dashboard says nothing about an asynchronous path.
- The outbox is what makes this recoverable: the order and its event were written in **one
  transaction**, so there is no state where one exists without the other.
- The order of checks matters — the database question is cheaper than the broker question, and it
  answers "did it ever leave?" definitively.
- **Do not "fix" this by updating the order row.** The settlement is what decrements stock; an order
  forced to `CONFIRMED` by hand is stock that was never reserved.

---

### 3.3 Investigation 3 — "Stock lookups work fine, but orders stopped confirming"

**Reported:** the stock API is fine. Orders are not.

This one exists because the obvious tool gives the wrong answer with total confidence.

#### Step 1 — the wrong turn

> **Ask the gateway whether Inventory is reachable.**

```bash
curl -s http://localhost:8001/upstreams/inventory-service.upstream/health
```

```
  inventory-service:8082   HEALTHY
```

And the public API agrees:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/api/v1/stock/SKU-1 -H "Authorization: Bearer $TOKEN"
```

```
  200
```

Measured **during** an active fault on both `inventory-http` and `inventory-grpc`. Kong is not lying —
***its*** path to the Inventory Service is genuinely healthy, because **Kong reaches Inventory
directly while the Order Service reaches it through Toxiproxy.**

Kong cannot see the broken path. Using its health page to answer "is Inventory reachable?" answers a
different question than the one asked.

#### Step 2 — ask the caller that is actually failing

```bash
curl -s http://localhost:9090/api/v1/query \
  --data-urlencode 'query=grpc_client_channel_state{state="TRANSIENT_FAILURE"}'
curl -s http://localhost:9090/api/v1/query \
  --data-urlencode 'query=resilience4j_circuitbreaker_state{state="open"}'
```

The Order Service's *client* view is where a service-to-service fault is visible. `GrpcChannelNotReady`
and `GrpcCircuitBreakerOpen` exist for exactly this blind spot.

#### Step 3 — the twist

Place an order with both synchronous paths still broken:

```
  order created: ORD-20260722-302A8A85
  status after 10s -> CONFIRMED
```

**It confirmed anyway.** The gRPC express reservation failed on its 200 ms deadline, and the Kafka path
settled the order a moment later — which is the fallback behaving exactly as designed. Breaking both
synchronous paths degrades *latency of settlement*, not correctness.

So the original report — "orders stopped confirming" — was, on this stack, not caused by the
synchronous path at all. To actually stop settlement you have to break Kafka (§3.2).

#### What it teaches

- **A health check tells you about the path it takes, not about the component.** Kong's view of
  Inventory and the Order Service's view of Inventory are different facts.
- The signal for a service-to-service fault lives on the **client**: channel state, circuit-breaker
  state, deadline counters.
- A working fallback can hide the fault you were sent to find. `CONFIRMED` did not mean the
  synchronous path was healthy — it meant the asynchronous one covered for it.

---

### 3.4 Investigation 4 — "We deployed the fix ten minutes ago and the graph still shows it broken"

**Reported:** the fix is in, the dashboard disagrees, somebody is about to roll back.

#### Step 1 — check the thing that recovers instantly

```bash
curl -s http://localhost:9090/api/v1/query --data-urlencode 'query=hikaricp_connections_pending'
```

```
  0
```

Zero, immediately after the fix. The queue drained. The system **is** healthy.

#### Step 2 — the metric that disagrees

```bash
curl -s http://localhost:9090/api/v1/query --data-urlencode 'query=lab:http_latency_p99:5m{service="order-service"}'
```

| When | p99 |
| --- | --- |
| 45 s after the fix | **9172.1 ms** |
| 90 s after the fix | **9179.6 ms** |
| Once the window rolled | **20.2 ms** |

Nothing was wrong. `lab:http_latency_p99:5m` is a **five-minute** window, and for five minutes after
the fix that window still contains the slow requests. A recording rule cannot forget faster than its
own range.

#### Step 3 — the query that shows recovery

```promql
histogram_quantile(0.99,
  sum by (le) (rate(http_server_requests_seconds_bucket{service="order-service"}[1m])))
```

```
  21.3 ms
```

Same data, one-minute window, recovery visible five times sooner.

#### What it teaches

- **Every rate and quantile has a window, and after a fix the window is what you are looking at.**
- Keep a short-window panel next to the alerting one. The 5 m version is right for *alerting* (it does
  not flap); the 1 m version is right for *watching a fix land*.
- The rollback that nearly happened here would have been triggered by arithmetic, not by a fault.

---

## 4. Pivoting between signals

The point of one shared vocabulary — `trace_id`, `span_id`, `request_id`, `correlation_id`, `service`,
`environment` — is that each of these is a click, not a search.

### Metric → trace

A slow p99 is an aggregate; a trace is one request. Go from one to the other by time window and
service, in Grafana → Explore → Tempo:

```traceql
{resource.service.name="order-service"} | select(span.order.number)
```

**Watch out:** background work dominates by volume. `OutboxRelay.publishPending` runs every second, so
an unfiltered search returns mostly relay traces. Filter by span attribute or duration.

### Trace → log

Every log line carries the `trace_id` of the request that produced it:

```logql
{service="order-service"} |= "e09504c157e7e553c297fcb6716eeaaa"
```

### Log → trace (verified, and usually the easier direction)

You almost always start from something a *user* names — an order number — not from a trace id.

```bash
# 1. find the order's log line, read its trace_id out of the JSON
curl -s -G http://localhost:3100/loki/api/v1/query_range \
  --data-urlencode 'query={service="order-service"} |= "ORD-20260722-A7938A48" | json | line_format "{{.trace_id}}"' \
  --data-urlencode "start=$(( ($(date +%s) - 600) ))000000000" \
  --data-urlencode "end=$(date +%s)000000000" --data-urlencode 'limit=5'
#   -> e09504c157e7e553c297fcb6716eeaaa

# 2. fetch that exact trace
curl -s http://localhost:3200/api/traces/e09504c157e7e553c297fcb6716eeaaa
#   -> HTTP 200, 47 spans
```

Which yields, across both services:

```
inventory-service   SELECT freepdb1.processed_events           1.02ms
inventory-service   SELECT freepdb1.stock_levels               1.08ms
inventory-service   UPDATE freepdb1.stock_levels               0.89ms
inventory-service   INSERT freepdb1.stock_movements            0.79ms
inventory-service   INSERT freepdb1.processed_events           0.67ms
```

That first `SELECT` is the idempotency check — the reason a redelivered event does not decrement stock
twice.

### Trace → profile

The Pyroscope OTel extension stamps the active span id onto profile labels, which is what turns "this
span was slow" into "and here is the CPU it burned". From a span in Grafana, follow the **Profiles**
link, or query directly:

```bash
curl -s "http://localhost:4040/pyroscope/render?query=process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name=\"order-service\"}&from=$(( $(date +%s) - 1800 ))&until=$(date +%s)&format=json"
```

Top self-time frames on this stack under load:

```
   8.2%  libc.so.6
   5.7%  libc.so.6.__write
   3.7%  libc.so.6.writev
   3.6%  libc.so.6.pthread_cond_signal
```

**That is the correct answer, and it surprises people.** The profiler runs on `itimer`, which samples
on a wall clock and therefore attributes *blocked* time. A service that spends its life writing JSON
logs, JDBC packets and HTTP responses shows write syscalls at the top. With `PYROSCOPE_PROFILER_EVENT=cpu`
this graph would be nearly empty and you would conclude, wrongly, that nothing was happening.

---

## 5. What each tool is bad at

Knowing this stops you looking in the wrong place, which is most of what makes debugging slow.

### Prometheus

| Bad at | Consequence |
| --- | --- |
| Anything per-request | It is aggregates. "Which order was slow" is not a question it can answer |
| Recent changes, when the window is long | A 5 m recording rule lags a fix by 5 m (§3.4) |
| History | **Retention is 1 day.** Yesterday's incident is in VictoriaMetrics (30 days), same PromQL |
| High cardinality | Adding an order id as a label would take it down |
| Telling "zero" from "missing" | A meter that was never registered and a meter reading zero look identical on a graph |

That last one is why `server.tomcat.mbeanregistry.enabled: true` is set: without it the
`tomcat_threads_*` meters do not exist, and `TomcatThreadPoolSaturated` is not quiet — it is *unable
to fire*.

### Loki

| Bad at | Consequence |
| --- | --- |
| Searching by anything that is not a label | Order number, customer, trace id are all **in the line**. Use `\|=` or `\| json` |
| High-cardinality labels | Only 7 labels exist, deliberately: `service`, `job`, `level`, `pipeline`, `environment`, `filename`, `service_name` |
| Being the source of truth for whether logs exist | A stopped shipper looks exactly like a quiet system. Check `up{job="fluent-bit"}` |

### Tempo

| Bad at | Consequence |
| --- | --- |
| Searching immediately after the fact | **A trace is fetchable by ID at once, but searchable by attribute only after a flush** — a minute or two. An empty search right after placing an order is usually flush lag |
| Signal-to-noise | `OutboxRelay.publishPending` produces a trace every second and buries the interesting ones |
| Aggregate questions | "What is p99" is Prometheus' job. Tempo answers "where did *this* request spend its time" |

### Pyroscope

| Bad at | Consequence |
| --- | --- |
| Answering "is there a leak" from allocation | Allocation volume says nothing about what is **retained**. Use the live-heap profile |
| Being queried the way its documentation used to say | This is Grafana Pyroscope 1.x: `/api/apps` **404s**. Use `/pyroscope/render` or the `querier.v1.QuerierService` endpoints |
| Short events | It samples. A 5 ms method that runs once will not appear |

### The health endpoints

| Bad at | Consequence |
| --- | --- |
| Telling you about a path they do not take | Kong's upstream health says nothing about the Order→Inventory path (§3.3) |
| Distinguishing "dependency down" from "should restart" | That is why liveness and readiness are different groups |

---

## 6. A general procedure

1. **`./scripts/chaos.sh list`.** Always.
2. **Reproduce it, and bound it.** When did it start; is it still happening.
3. **Metrics to locate the layer** — edge, service, pool, broker, dependency.
4. **Check for disagreement between two views of one thing.** It localises the fault between them.
5. **Traces to find where the time went** in one request.
6. **Logs, by `trace_id`, for what the code said** while it was there.
7. **Profiles for which method**, if the time is in the process rather than waiting on someone else.
8. **Name the wrong turn you took** when you write it up. It is the most useful part for the next
   person, and it is the part everybody omits.

---

## 7. Practise this

[Exercises.md](Exercises.md) turns each of these into a question with a checkable answer. The
scenarios themselves are one command each:

```bash
./scripts/scenario.sh list
./scripts/scenario.sh slow-request --under-load
```

Expected signals for all 13 are written down **before** the run in
[FailureSimulation.md](FailureSimulation.md), which is the discipline that makes any of this evidence
rather than anecdote.

---

## 8. Related

| Document | For |
| --- | --- |
| [Observability.md](Observability.md) | What each signal is for, and the symptom map |
| [Exercises.md](Exercises.md) | Graded practice with checkable answers |
| [Simulation.md](Simulation.md) | Network faults and load, signal by signal |
| [FailureSimulation.md](FailureSimulation.md) | 13 in-process scenarios, plus 2 for the secret store |
| [Troubleshooting.md](Troubleshooting.md) | Symptom → cause → fix, when you need the answer not the method |
| [Metrics.md](Metrics.md) / [Logging.md](Logging.md) / [Tracing.md](Tracing.md) / [Profiling.md](Profiling.md) | The reference behind each signal |
| [Alerting.md](Alerting.md) | Which alert fires, when, and what to do first |
