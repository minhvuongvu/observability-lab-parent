# Performance

What this system's numbers mean, what limits them, how to measure honestly, and which knob moves what.

> **The one thing to read if you read nothing else.** This lab is calibrated to saturate at around
> **10 orders/s**. That ceiling is a deliberate property, not a defect: the Order Service runs with a
> 10-connection pool and a 1.5-CPU limit so that queueing, pool exhaustion and GC pressure are
> reachable in ninety seconds on a laptop. Raising the limits to make a number look better deletes the
> result.
>
> Scenario-by-scenario expectations are in [Simulation.md](Simulation.md). This document is the
> reference behind them: the budgets, the limits, the method, and the tuning consequences.

---

## 1. What performance means here

This is not a benchmark. Nothing in the domain is expensive — an order is an insert and a decrement.
Every number below is a property of the *configured envelope*, chosen so that failure modes appear on
one machine in a short run.

| Question this lab answers | Question it does not |
| --- | --- |
| Which resource runs out first, and what does that look like in each signal? | How fast can Spring Boot go? |
| Is degradation graceful, and where is the knee? | How does this compare to another framework? |
| What does a 400 ms dependency do to a saturated system versus an idle one? | What throughput would this reach on real hardware? |
| Which metric is the leading indicator, and which is the lagging one? | — |

A performance number produced on a Docker Desktop VM, against a single-node everything, with 100%
trace sampling and continuous profiling attached, is a number about *this configuration*. That is
still worth measuring, because the shape of the degradation is real even when the magnitude is not.

---

## 2. The measured ceiling

Measured on the shipped configuration, offered as a fixed arrival rate:

| Offered rate | Result |
| --- | --- |
| 10/s | 0% errors, p95 **49 ms**, p99 134 ms — every threshold passes |
| 20/s | **28% errors**, p95 5.7 s |
| 40/s | **53% errors**, ~130 requests queued on the connection pool |

Every failure is the same one:

```
order-db-pool - Connection is not available, request timed out after 3000ms
(total=10, active=10, idle=0, waiting=134)
```

That is the whole story of this lab's capacity: **the database connection pool is the binding
constraint**, and it binds long before CPU, heap or the broker do. Everything in §5 is downstream of
that sentence.

The k6 defaults are calibrated against these numbers. `load.js` offers 10/s because that is the rate
the system handles cleanly, and its thresholds are meaningful only at that rate.

---

## 3. The latency budget

Timeouts are layered so that the **innermost** component reports the failure. The rule, stated once:

> A callee's budget must be smaller than its caller's, or the callee's timeout never fires, the caller
> times out first, and the more specific error is lost.

```
client
└─ Nginx        proxy_connect_timeout 2s   proxy_read_timeout 20s
   └─ Kong      connect_timeout 2s         read_timeout 15s
      └─ Order Service   HTTP request budget      3000ms
         ├─ order handler                          1000ms
         │  └─ gRPC BatchCheckStock                 300ms
         │     └─ Oracle query                      250ms
         ├─ Hikari connection-timeout              3000ms
         ├─ Redis timeout / connect-timeout        2000ms
         └─ Feign inventory-service   connect 2000ms   read 5000ms
```

| Budget | Value | Why that value |
| --- | --- | --- |
| Nginx read | 20 s | **Longer** than Kong's, so a slow upstream is reported by the component that knows why rather than cut off at the edge |
| Kong read | 15 s | Shorter than the service's own budget, so a hung upstream surfaces as a gateway timeout rather than a client on an open socket |
| Feign read | 5 s | Shorter than the caller's request budget, so a slow dependency surfaces as a 504 here rather than as our caller's timeout |
| gRPC `CheckStock` | 200 ms | Synchronous, on the request path |
| gRPC `BatchCheckStock` | 300 ms | One round trip for the whole basket — was 25 round trips over REST |
| gRPC `ReserveStock` | 200 ms | Deliberately tight. This is an optimisation; missing it costs nothing but a fall-through to `PENDING` |
| Hikari connection | 3 s | Fail fast rather than piling threads up behind an unreachable database |
| Redis | 2 s | The cache must never become the slowest path |
| Kafka `delivery.timeout.ms` | 30 s | Durability over latency: `acks=all`, idempotent producer |

**The express reservation is the interesting one.** It races the Kafka path with a 200 ms deadline. If
it wins, the order settles synchronously; if it loses, nothing is lost — the Kafka path settles it a
moment later. A tight deadline on an optimisation is free; a tight deadline on the only path is an
outage.

---

## 4. The limits, and what each one bounds

| Limit | Value | Bounds |
| --- | --- | --- |
| `ORDER_SERVICE_CPUS` | 1.5 | CPU-bound work, and how quickly GC keeps up |
| `ORDER_SERVICE_MEMORY` | 768M | Container ceiling; heap is 70% of it via `MaxRAMPercentage` |
| `INVENTORY_SERVICE_CPUS` / `_MEMORY` | 1.5 / 768M | Same |
| Hikari `maximum-pool-size` | 10 | Concurrent database work. **The binding constraint** |
| Hikari `minimum-idle` | 2 | How much of a burst is absorbed before new connections are opened |
| Tomcat threads | Boot default (200) | Concurrent requests in flight before queueing |
| Kafka listener `concurrency` | 3 | Consumer threads per service, matching the **3 partitions** exactly — one thread per partition |
| `max-poll-records` | 50 | Batch size per poll |
| Hibernate `jdbc.batch_size` | 25 | Statements per round trip |
| `spring.data.web.pageable.max-page-size` | 100 | A ceiling on what a caller may ask for — without it, `size=1000000` loads the table into memory |
| Kong rate limit | 120/min, 3000/hour per route | Offered load *through the edge* |
| Nginx / Kong body size | 1 MB each | The largest request the heap must buffer |

**`MaxRAMPercentage=70`, never `-Xmx`.** The heap is sized from the container limit, so changing
`ORDER_SERVICE_MEMORY` changes the heap and the two cannot drift apart. `-XX:+ExitOnOutOfMemoryError`
is set as well: an OOM is a fast exit and a restart, not a JVM limping along in a state nobody can
reason about.

The full stack of memory ceilings, and why their sum (15.6 GB) is not a reservation, is in
[Deployment.md §2](Deployment.md#2-prerequisites).

---

## 5. What runs out first

In order, measured:

1. **The connection pool.** 10 connections, 3 s timeout. At 20/s the pool is fully active and requests
   queue; at 40/s ~130 are waiting. `hikaricp_connections_pending > 0` is the leading indicator, and
   `DatabasePoolExhausted` fires on it after 2 minutes.
2. **Tomcat threads**, if the pool is widened. Its whole signature is that CPU, heap and the database
   all look healthy — which is why `server.tomcat.mbeanregistry.enabled: true` is set. Without it the
   `tomcat_threads_*` meters do not exist, and `TomcatThreadPoolSaturated` is not quiet, it is unable
   to fire.
3. **CPU**, at 1.5 cores, once neither of the above is limiting.
4. **Heap and GC**, at 768M × 70%. `JvmHeapPressure` at >90% for 10 minutes, `JvmGcPauseTimeHigh` on
   pause ratio.
5. **Kafka consumer throughput**, at `concurrency: 3`. Shows up as lag, never as latency on the
   synchronous path — which is precisely the decoupling the asynchronous design bought.

The ordering is the point of the calibration. Each of these is reachable, in sequence, by widening the
one above it — which makes "find the next bottleneck" an exercise rather than a thought experiment.

### Little's law, applied here

`concurrency = arrival rate × latency`. At 10/s with a 49 ms p95, roughly **0.5 requests** are in
flight on average — comfortably inside a 10-connection pool. At 20/s, latency rises to 5.7 s, and
`20 × 5.7 = 114` concurrent requests against 10 connections. That is not a coincidence; it is the same
number the pool timeout message reports as `waiting=134`.

The lesson generalises: a pool of *N* with a mean service time *S* can sustain at most `N / S`
arrivals per second. Everything past that queues, and queueing is what turns a 49 ms p95 into a 5.7 s
one without any single operation getting slower.

---

## 6. How to measure

### The protocol

```bash
./scripts/chaos.sh reset          # 1. no leftover fault. This is not optional.
./scripts/infra.sh health         # 2. everything converged
./scripts/load.sh smoke           # 3. is the path working at all?
./scripts/load.sh load            # 4. the actual run
```

Then read `dropped_iterations` in the k6 summary **before** anything else. If it is non-zero, k6 could
not sustain the offered load and the run measured k6, not the system. `K6_MEMORY` is 512M by default.

**Change one variable per run.** Two changes and a moved number is a coincidence with extra steps.

### The scenarios

| Scenario | Shape | Default | The question it answers |
| --- | --- | --- | --- |
| `smoke` | 1 VU, 1 min | — | Is every component on the path actually working? |
| `load` | ramp 1 m, hold | `RATE=10`, `DURATION=5m` | How does the system behave at the traffic it is meant to handle? |
| `stress` | staged climb | `PEAK=100` | Which resource runs out first, and is the failure graceful? |
| `spike` | idle → wall → idle | `PEAK=80` | What do the cold paths do, and how long is recovery? |
| `soak` | constant, hours | `RATE=5`, `DURATION=2h` | What accumulates — leaks, lag, unreturned connections? |

```bash
RATE=20 DURATION=10m ./scripts/load.sh load
PEAK=300 ./scripts/load.sh stress
BASE_URL=http://nginx ./scripts/load.sh load      # through the edge — measures the rate limiter
```

### Arrival rate, not virtual users

Every scenario uses one of k6's `arrival-rate` executors, and the distinction matters more than it
looks.

With a fixed number of virtual users, each waits for its response before sending the next request — so
a system that slows down *receives less traffic*, and hides its own degradation behind a throughput
number that stays flat. That is the single most common way a load test lies.

A fixed arrival rate offers the same load regardless of how the system copes. Saturation then appears
where it should: rising latency, a rising VU count, and eventually `dropped_iterations`.

### Results land beside the system's own metrics

`load.sh` runs k6 with `--out experimental-prometheus-rw`, remote-writing every k6 metric into the same
Prometheus that scrapes the platform — which is why Prometheus runs with
`--web.enable-remote-write-receiver`.

That puts the load and its effect on **one time axis**: `k6_http_req_duration` beside
`jvm_gc_pause_seconds` beside `hikaricp_connections_pending`, at the same instant. A load test whose
results live in a separate tool leaves you eyeballing two clocks.

`K6_PROMETHEUS_RW_TREND_STATS` requests native histograms, so a p99 computed in Grafana is exact rather
than interpolated from k6's default summary quantiles — which cannot be aggregated across runs at all.

### k6 runs inside the network

k6 is a container on `lab-net`, not a process on the host. It therefore experiences the same network
the services do, rather than the host's loopback stack, which has different latency characteristics and
no notion of the gateway. A load generator outside the system it measures is measuring the wrong thing.

---

## 7. What each signal should show at 10/s

| Signal | Expectation | What a deviation means |
| --- | --- | --- |
| **HTTP** | p95 < 500 ms (measured ~49 ms), 0% errors | Past the SLO buckets configured on `http.server.requests` |
| **JVM heap** | Sawtooth, flat floor after each full GC | A **rising floor** is accumulation, not load |
| **GC** | Pause time < 1% of wall clock | More means the heap limit is the bottleneck, not the CPU |
| **Tomcat threads** | Busy well under max | At max, requests queue and latency grows with no CPU rise |
| **Hikari** | Active < pool size, `pending` at 0 | Sustained `pending` is the database, not the service |
| **Kafka** | Producer rate tracks order rate; lag near 0 | Growing lag means the consumer is slower than the producer |
| **Profiles** | Flame graph dominated by JDBC and serialisation | Anything else dominating is the surprise worth reading |

The last row is the one people skip. Under load the flame graph reshapes, and the method that grows is
usually not the one anybody would have guessed.

### The SLO buckets

```yaml
management.metrics.distribution:
  percentiles-histogram:
    http.server.requests: true
  slo:
    http.server.requests: 50ms,100ms,250ms,500ms,1s,2s
```

Server-side percentiles from bucket counts, not client-side pre-computed ones: a pre-computed 99th
percentile **cannot be aggregated across instances**, so it becomes meaningless the moment this scales
out. The explicit SLO boundaries mean "what fraction of requests were under 500 ms" is an exact bucket
ratio rather than an interpolation.

---

## 8. Tuning — what each knob moves, and what it costs

Every row is a legitimate experiment. Every row also invalidates the calibrated defaults, so re-measure
before comparing to any number in §2.

| Knob | Raising it | Cost / what to re-measure |
| --- | --- | --- |
| Hikari `maximum-pool-size` | Moves the ceiling up until the next constraint binds | The database now takes the contention. Watch `pg_stat_activity` and PostgreSQL's own `max_connections` |
| `ORDER_SERVICE_CPUS` | Faster GC, faster serialisation | On a 4-core laptop, two services at 3.0 CPU each will contend with the 33 other containers |
| `ORDER_SERVICE_MEMORY` | Larger heap (70%), fewer GCs | GC pressure and heap alerts stop happening — which is deleting a symptom the lab exists to show |
| Kafka `concurrency` | Nothing, on its own | Already 1:1 with the **3** partitions. More consumer parallelism needs more partitions first (`KAFKA_TOPIC_PARTITIONS`), and that only applies to a topic created on an empty broker volume |
| `max-poll-records` | Fewer polls, larger batches | Longer time between commits; a crash replays more |
| Hibernate `batch_size` | Fewer round trips on multi-row writes | Larger transactions, longer locks |
| Kong `minute` rate limit | More traffic reaches the service | The edge stops being the protection it exists to be |
| `K6_SKU_COUNT` | Orders spread over more SKUs | **Lowers** Oracle row contention and Redis hit rate. It is 20 on purpose: low enough that SKUs stay cached and contend on the same rows, which is what produces lock contention worth profiling |
| gRPC deadlines | Fewer `DEADLINE_EXCEEDED` | A slow dependency now consumes the caller's budget instead of failing fast |
| Circuit breaker thresholds | Fewer trips | The breaker exists to stop a slow dependency from becoming your outage. Raising it because it tripped is deleting the defence |

### Two knobs that should not be raised

- **`app.chaos.max-*` ceilings.** They bound what a *caller* may ask for, not defaults. An unbounded
  memory leak is an OOM kill that takes the JVM down before anyone reads the flame graph it was
  supposed to produce.
- **Trace sampling.** It is `parentbased_always_on` — 100% — because the lab exists to be looked at.
  Lowering it is correct for production and wrong here.

---

## 9. The cost of observing

The stack observes itself continuously, and that is not free. Being honest about it is part of reading
any number above.

| Source | What it costs |
| --- | --- |
| OpenTelemetry agent, 100% sampling | Bytecode instrumentation on every instrumented call, plus OTLP export |
| Pyroscope agent, `itimer` + alloc `512k` + lock `10ms` | Continuous sampling, uploaded every 10 s |
| Micrometer histograms with SLO buckets | More series, more memory in the registry, larger scrapes |
| JSON logging to file **and** stdout under `dev` | Two encodings of every line |
| Prometheus at 10 s on the services, 15 s elsewhere | Scrape load on each target |
| Toxiproxy in the path of every dependency hop | An extra TCP relay per connection, even with no toxics |

None of these is optional in this lab — removing them removes the thing being taught. But it means:

> **A latency number from this stack includes the cost of watching it.** If a comparison against
> something else matters, disable the agents (drop the `-javaagent` lines from `JAVA_TOOL_OPTIONS`)
> and re-measure. That is a legitimate experiment, and the delta is itself worth knowing.

The Toxiproxy hop is the easiest one to remove: point `ORDER_DB_HOST` at `postgres`, `REDIS_HOST` at
`redis` and so on in `.env`, and the relay leaves the path entirely.

---

## 10. Performance under fault

A fault injected into an idle system tells you what the mechanism does. A fault injected into a
saturated one tells you what it does when it matters, and the two are frequently different answers.

```bash
./scripts/load.sh load &
sleep 60 && ./scripts/chaos.sh slow postgres 400
```

**200 ms of added latency is nothing on an idle system and is enough to collapse a saturated one.**
That is arithmetic, not drama: a 10-connection pool with a mean service time of 50 ms sustains 200/s;
add 400 ms and the same pool sustains 22/s. Offered load did not change, so everything above 22/s
queues, and the queue is what the user experiences.

`scenario.sh <name> --under-load` runs any of the 13 scenarios with k6 offering traffic underneath.
[Simulation.md](Simulation.md) scenario 7 is the worked example, signal by signal.

---

## 11. Non-functional targets

Lab-scale, chosen to be observable on one host rather than to be impressive. Reproduced here from
[SystemDesign.md §11](SystemDesign.md#11-non-functional-targets) because a performance document that
does not state its targets is a collection of numbers.

| Property | Target |
| --- | --- |
| Order creation latency (p95, happy path) | < 300 ms end to end through the gateway |
| Feign call timeout | 2 s connect, 5 s read |
| gRPC deadline — `CheckStock` | 200 ms |
| gRPC deadline — `BatchCheckStock` | 300 ms |
| gRPC deadline — `ReserveStock` | 200 ms; missing it falls through to the Kafka path |
| Batch stock check, 25 lines | 1 round trip (was 25 over REST) |
| Kafka end-to-end lag (steady state) | < 5 s from `order-created` to order confirmation |
| Service startup | < 30 s to healthy |
| Graceful shutdown | ≤ 30 s (Order), ≤ 45 s (Inventory, to finish the current batch) |
| Trace sampling | 100% in `local` and `dev` |
| Metric scrape interval | 15 s (10 s for the services) |

### Retention — the targets and what actually ships

[SystemDesign.md §11](SystemDesign.md#11-non-functional-targets) states retention as a *target*. The
shipped configuration is more conservative, and the gap is large enough to matter when you go looking
for yesterday's incident:

| Signal | Target | **Actually configured** | Where |
| --- | --- | --- | --- |
| Logs — Loki | 7 days | **7 days** (`retention_period: 168h`) | `infrastructure/loki/loki.yml` |
| Traces — Tempo | — | **7 days** (`block_retention: 168h`) | `infrastructure/tempo/tempo.yml` |
| Metrics — Prometheus | 15 days | **1 day** (`--storage.tsdb.retention.time=24h`) | compose |
| Metrics — VictoriaMetrics | 90 days | **30 days** (`--retentionPeriod=30d`) | compose |

Verified live against `/api/v1/status/flags`. **Prometheus keeps one day**, which is why
VictoriaMetrics receives a `remote_write` copy of every sample — a comparison across last week's runs
has to be made there, not in Prometheus. Raising Prometheus' retention is a legitimate change; it
costs disk in `lab-prometheus-data` and nothing else.

The batch stock check row is the one measured improvement in the system rather than a chosen target: it
came from a real N+1 defect in the REST path, and the justification is in
[GRPC_ENHANCEMENT_ANALYSIS.md](../GRPC_ENHANCEMENT_ANALYSIS.md).

---

## 12. What not to conclude

| Do not conclude | Because |
| --- | --- |
| "This stack tops out at 10/s" | It tops out at a **10-connection pool**. Widen it and the next constraint binds |
| "p95 is 49 ms, so the code is fast" | It is 49 ms with one client, one node, warm caches, and no network between tiers |
| "Throughput stayed flat, so nothing degraded" | With a fixed arrival rate, flat throughput and rising errors is exactly what degradation looks like |
| "CPU is idle, so there is headroom" | The pool binds first. Idle CPU next to a saturated pool is the normal shape of this failure |
| "The database is fine, the exporter says so" | Exporters bypass Toxiproxy on purpose. Disagreement between them means the **path** is the fault |
| "Adding a replica doubles capacity" | Both replicas share PostgreSQL, Oracle and Redis, and the outbox relay polls the same table |
| "These numbers transfer" | Docker Desktop's VM, single-node everything, and full instrumentation. The *shape* transfers; the magnitude does not |

---

## 13. Related documents

| Document | For |
| --- | --- |
| [Simulation.md](Simulation.md) | The scenarios, and what every signal should show |
| [FailureSimulation.md](FailureSimulation.md) | The 13 in-process faults, expectations written first |
| [Metrics.md](Metrics.md) | Instrument types, cardinality, histograms vs pre-computed percentiles |
| [Observability.md](Observability.md) | Where to look, by symptom |
| [Alerting.md](Alerting.md) | The thresholds that turn these numbers into a page |
| [Deployment.md](Deployment.md) | Where the limits live, and what must change with them |
| [Profiling.md](Profiling.md) | Reading the flame graph the last row of §7 refers to |
