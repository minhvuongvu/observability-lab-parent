# gRPC Failure Simulation

Chaos scenarios for the gRPC hop. Each one breaks something deliberately and states **exactly what
every signal should show** — because a resilience mechanism that has never been observed working is
an assumption, and an alert that has never fired is untested.

This extends the failure-simulation work of step 17. The endpoints and toggles here are guarded the
same way: `local` and `dev` profiles only, `ADMIN` role required, never enabled in `prod`.

---

## How to read a scenario

Each has four parts:

1. **Inject** — how to cause it
2. **Expected** — what each signal should show, per signal
3. **Verify** — the concrete query or command
4. **What it teaches** — the failure this makes legible

If the observed behaviour differs from **Expected**, the finding is a gap in the observability or the
resilience configuration, not a mistake in the scenario. That is the point of writing the expectation
down first.

---

# Scenario 1 — Inventory Service unavailable

The canonical dependency failure. Everything that follows is a variation on it.

## Inject

```bash
# The blunt version: stop the service entirely
docker compose stop inventory-service     # once containerised
# or, running on the host:
kill $(pgrep -f inventory-service.*jar)

# The subtler version: keep the process, refuse gRPC only
curl -X POST localhost:8082/api/v1/chaos/grpc/reject \
  -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"status":"UNAVAILABLE","ratio":1.0}'
```

The second form matters: it keeps the HTTP health check green while gRPC fails, which is a real and
nastier failure than a dead process.

## Expected

| Signal | Expectation |
| --- | --- |
| **gRPC client** | `UNAVAILABLE` immediately. Retry policy makes 3 attempts with 50/100 ms backoff, then gives up |
| **Circuit breaker** | Opens after 20 calls at >50% failure. Subsequent calls fail **without contacting** the dependency |
| **Fallback** | `BatchCheckStock` returns a degraded answer (`tracked=false`, flagged). `ReserveStock` falls through to Kafka; order accepted as `PENDING` |
| **User impact** | **None on order acceptance.** Availability display degrades. This is the whole design paying off |
| **Metrics** | `grpc_client_requests_total{grpc_status="UNAVAILABLE"}` rises · `grpc_client_channel_state{state="TRANSIENT_FAILURE"}` = 1 · `resilience4j_circuitbreaker_state{state="open"}` = 1 |
| **Logs** | `WARN` on the client with `grpc_status=UNAVAILABLE`. **No `ERROR`** — the caller degraded successfully |
| **Traces** | Client span with `ERROR` status and `rpc.grpc.status_code=14`. **No server span** — the call never arrived. That absence is the diagnostic |
| **Alerts** | `GrpcChannelNotReady` after 2 min |

## Verify

```promql
sum(rate(grpc_client_requests_total{grpc_status="UNAVAILABLE"}[1m]))
grpc_client_channel_state{state="TRANSIENT_FAILURE"}
resilience4j_circuitbreaker_state{name="inventory-grpc",state="open"}
```

```logql
{service="order-service"} | json | grpc_status="UNAVAILABLE"
```

Then confirm orders still work:

```bash
curl -X POST localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -d '{"customerId":"C-1","currency":"EUR","items":[{"productSku":"SKU-1","quantity":1,"unitPrice":"9.99"}]}'
# expect 201 PENDING — accepted despite Inventory being gone
```

## What it teaches

**A missing span is evidence.** The client span exists with an error; no server span was ever
created. That gap is how you distinguish "the callee rejected it" from "the callee never heard it" —
and it is invisible in metrics, which only show a failure count on the client.

It also demonstrates the design's central claim: the asynchronous path is the invariant, so a
synchronous dependency being *entirely gone* costs latency and display quality, not orders.

---

# Scenario 2 — Slow gRPC responses

More dangerous than an outage. An outage fails fast; slowness consumes resources on both sides.

## Inject

```bash
# 800ms added latency on every gRPC handler — well past the 300ms deadline
curl -X POST localhost:8082/api/v1/chaos/grpc/latency \
  -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"delayMs":800,"ratio":1.0}'

# The realistic version: only some calls, so averages stay deceptively healthy
curl -X POST localhost:8082/api/v1/chaos/grpc/latency \
  -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"delayMs":800,"ratio":0.1}'
```

## Expected

| Signal | Expectation |
| --- | --- |
| **Client** | `DEADLINE_EXCEEDED` after 300 ms — the client gives up **before** the server finishes |
| **Server** | The handler keeps running unless deadline-aware (§2.3 of [GRPC_ERROR_HANDLING.md](GRPC_ERROR_HANDLING.md)). With the check in place it aborts early and returns `DEADLINE_EXCEEDED` itself |
| **Retry** | **No retry.** `DEADLINE_EXCEEDED` is deliberately not in the retryable set — the budget is already gone |
| **Circuit breaker** | Opens on the **slow-call** threshold (80% over 250 ms), not the error threshold. This is why a pure error-rate breaker is insufficient |
| **Metrics** | `grpc_server_request_duration_seconds_bucket` shifts right · `grpc_status="DEADLINE_EXCEEDED"` rises · `grpc_server_active_calls` climbs as calls pile up |
| **Logs** | `WARN` with `grpc_status=DEADLINE_EXCEEDED` and `grpc_duration_ms` ≈ the deadline, not the true handler time |
| **Traces** | Client span ~300 ms with error; server span ~800 ms **and longer than its parent** — the visual signature of a deadline breach |
| **Profiles** | With the 10% variant, `itimer` shows the blocked time. `cpu` would show almost nothing — which is why this lab uses `itimer` |

## Verify

```promql
# Where the latency distribution actually sits
histogram_quantile(0.99, sum by (grpc_method, le)
  (rate(grpc_server_request_duration_seconds_bucket[5m])))

# The gap that identifies the cause
histogram_quantile(0.99, sum by (le) (rate(grpc_client_request_duration_seconds_bucket[5m])))
histogram_quantile(0.99, sum by (le) (rate(grpc_server_request_duration_seconds_bucket[5m])))
```

## What it teaches

**A child span longer than its parent** is impossible in a healthy trace and unmistakable once seen.
It says the caller stopped waiting while the callee kept working — the definition of wasted capacity.

The 10% variant teaches something sharper: **the mean stays healthy while the p99 collapses.** A
dashboard built on averages shows nothing at all. This is the concrete argument for histogram buckets
over pre-computed means, made visible.

---

# Scenario 3 — High gRPC traffic

Saturation, and the specific way gRPC saturation hides.

## Inject

```bash
# 500 concurrent streams, 60s, 50-line batches
ghz --insecure --proto proto/inventory/v1/inventory_service.proto \
    --call inventory.v1.InventoryService/BatchCheckStock \
    -d '{"items":[{"product_sku":"SKU-1","requested_quantity":1}, ...]}' \
    -c 500 -z 60s localhost:9082
```

## Expected

| Signal | Expectation |
| --- | --- |
| **Throughput** | Rises, then plateaus. The plateau is the capacity limit |
| **gRPC executor pool** | **Saturates first.** This is the finding |
| **DB pool** | `hikaricp_connections_pending` > 0 as handlers queue for Oracle |
| **Latency** | p50 stays flat while p99 climbs — queueing, not slower work |
| **Status** | `RESOURCE_EXHAUSTED` once the executor queue is full, if load shedding is configured; otherwise `DEADLINE_EXCEEDED` as everything slows |
| **CPU** | Rises, but **does not reach 100%** — the bottleneck is the pool, not the processor |
| **Heap** | Stable. Protobuf allocates far less than JSON per request |
| **HTTP traffic** | **Unaffected.** Tomcat's thread pool is separate — which is exactly what makes this hard to diagnose |

## Verify

```promql
executor_active_threads{name="grpc-executor"}
  / clamp_min(executor_pool_max_threads{name="grpc-executor"}, 1)

grpc_server_active_calls
hikaricp_connections_pending
process_cpu_usage
```

## What it teaches

**The invisible saturation.** Requests are slow. CPU is at 40%. Heap is fine. The database is idle.
Tomcat's thread pool is empty. Every dashboard built before gRPC existed shows a healthy service.

The queue is in front of a pool that nothing else measures — which is precisely why
`executor_active_threads{name="grpc-executor"}` is a first-class panel in
[GRPC_OBSERVABILITY.md §2.4](GRPC_OBSERVABILITY.md#24-use--the-resource-level-view) rather than an
afterthought.

It also demonstrates the protocol comparison concretely: run the same load through the REST endpoint
and compare CPU, allocation rate and achieved throughput. The numbers are the argument for gRPC, not
the marketing.

---

# Scenario 4 — Deadline propagation failure

Subtle, and it silently disables a resilience mechanism people believe is working.

## Inject

Configure a gRPC client **without** a deadline:

```bash
curl -X POST localhost:8081/api/v1/chaos/grpc/no-deadline \
  -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"enabled":true}'
```

Then apply Scenario 2's latency.

## Expected

| Signal | Expectation |
| --- | --- |
| **Client** | Waits **indefinitely**. No `DEADLINE_EXCEEDED`, because there is no deadline |
| **Threads** | Caller threads accumulate in `WAITING` |
| **Metrics** | `grpc_client_active_calls` climbs without bound · `jvm_threads_live_threads` rises on the **client** |
| **Traces** | Client spans that never close — and therefore **never appear in Tempo**, because a span is exported on end |
| **Cascade** | The Order Service's own HTTP requests start timing out. The symptom appears in the caller, not the culprit |

## Verify

```promql
grpc_client_active_calls
jvm_threads_live_threads{service="order-service"}
sum(rate(grpc_client_requests_total{grpc_status="DEADLINE_EXCEEDED"}[5m]))  # stays 0 — the tell
```

## What it teaches

**Traces have a blind spot: a span is exported when it ends.** An operation that never completes is
invisible in the trace backend for its entire lifetime — exactly when you most want to see it.

The gauge is what covers the gap. `grpc_client_active_calls` climbing while
`grpc_client_requests_total` stays flat means calls are starting and not finishing, and no trace will
tell you that. It is the same reason the outbox relay uses a `LongTaskTimer`.

---

# Scenario 5 — Contract violation

The failure the whole proto discipline exists to prevent.

## Inject

Deploy an Inventory Service whose proto reuses a field number:

```protobuf
message CheckStockResponse {
  string product_sku = 1;
  int32 available_quantity = 2;
  string warehouse_code = 3;   // ❌ 3 was reserved_quantity
}
```

## Expected

| Signal | Expectation |
| --- | --- |
| **CI** | `buf breaking` **fails the build.** In a correctly configured pipeline the scenario ends here |
| **If deployed anyway** | No error. The call succeeds. `reserved_quantity` decodes as garbage from the string bytes |
| **Metrics** | `grpc_status="OK"` throughout. **The fault rate does not move** |
| **Logs** | Nothing. Nothing was thrown |
| **Traces** | A normal, successful span |
| **Detection** | Only through **business** metrics: `lab_orders_settled_total{outcome="rejected"}` rises, or the availability answers stop matching reality |

## What it teaches

**Some failures are invisible to all four signals.** No error, no latency change, no log line — just
wrong answers, confidently returned.

This is precisely the class of bug that hit this system in step 09, when the Feign client decoded the
API envelope into the payload type and reported `availableQuantity: 0` for every SKU. It returned
`200 OK`. Nothing was logged. It was found by reading a number that looked wrong.

Two defences, in order:

1. **`buf breaking` in CI** — mechanical, catches it before it ships. This is the real control.
2. **Business metrics** — the only telemetry that would ever notice, which is why
   `lab_orders_*` exist alongside the technical metrics.

---

# Scenario 6 — Load-balancer pinning

The gRPC operational trap from [GRPC_ERROR_HANDLING.md §5](GRPC_ERROR_HANDLING.md#5-load-balancing).

## Inject

Set the channel policy to `pick_first` (the gRPC default) with two Inventory instances running:

```bash
curl -X POST localhost:8081/api/v1/chaos/grpc/lb-policy \
  -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"policy":"pick_first"}'
```

## Expected

| Signal | Expectation |
| --- | --- |
| **Distribution** | **100% to one instance.** The other is completely idle |
| **Metrics** | `grpc_server_requests_total` non-zero on one instance, zero on the other · CPU and pool metrics diverge sharply |
| **Latency** | Rises under load despite half the capacity being unused |
| **Scaling** | Adding a third instance changes **nothing** — the symptom that identifies the cause |
| **Traces** | Every server span reports the same `server.address` |

## Verify

```promql
sum by (instance) (rate(grpc_server_requests_total[5m]))   # one row non-zero
sum by (instance) (process_cpu_usage)                       # one hot, others idle
```

## What it teaches

**Scaling out that changes nothing is a protocol problem, not a capacity problem.** With HTTP/1.1
the same setup balances correctly, because each request may take a new connection. With gRPC the
connection is established once and pinned, and nothing about the configuration looks wrong.

Fixing it is one line — `round_robin` instead of the default — and finding it takes hours if you do
not know to look. That asymmetry is why it is a scenario.

---

# Scenario 7 — Streaming subscription leak

Specific to streaming, with no unary equivalent.

## Inject

Open 1,000 `WatchStockLevels` streams and abandon the clients without cancelling.

## Expected

| Signal | Expectation |
| --- | --- |
| **Server** | Streams stay open. Each holds a subscription and buffer |
| **Metrics** | `grpc_server_active_calls{grpc_type="SERVER_STREAMING"}` climbs and **never falls** |
| **Memory** | Heap grows steadily; `alloc_live` shows retention |
| **Detection** | Keepalive eventually reaps dead peers — **if configured**. Without it, they accumulate until OOM |
| **Traces** | Nothing. The spans have not ended |
| **Profiles** | Live-heap flame graph points at the subscription registry |

## Verify

```promql
grpc_server_active_calls{grpc_type="SERVER_STREAMING"}
jvm_memory_used_bytes{area="heap"}
```

Then Pyroscope → `memory:live` → look for the subscription holder.

## What it teaches

**A leak with no error and no latency signature.** The only early indicator is a gauge that goes up
and never comes down, and the only way to find the holder is a live-heap profile.

It is also the concrete justification for `keepAliveWithoutCalls: true`: without it, a stream to a
peer that vanished is indistinguishable from an idle healthy one, forever.

---

# Coverage matrix

| Scenario | Metrics | Logs | Traces | Profiles | Resilience exercised |
| --- | :---: | :---: | :---: | :---: | --- |
| 1 Unavailable | ✅ | ✅ | ✅ missing span | — | Retry, breaker, fallback |
| 2 Slow response | ✅ | ✅ | ✅ child > parent | ✅ | Deadline, slow-call breaker |
| 3 High traffic | ✅ | — | ✅ | ✅ | Load shedding, pool limits |
| 4 No deadline | ✅ gauge only | — | ❌ blind spot | — | Deadline enforcement |
| 5 Contract violation | ❌ business only | ❌ | ❌ | — | `buf breaking` in CI |
| 6 LB pinning | ✅ | — | ✅ | ✅ | Client-side balancing |
| 7 Stream leak | ✅ gauge only | — | ❌ | ✅ | Keepalive reaping |

The two ❌ columns in scenarios 4, 5 and 7 are the honest and useful part of this table. **Not every
failure is visible in every signal**, and knowing which signal covers which gap is the difference
between an investigation and a guess:

- **Traces are blind to what has not finished.** Gauges cover that.
- **All four signals are blind to a wrong answer.** Only business metrics and contract testing cover
  that.

---

# Running the suite

```bash
./scripts/chaos.sh grpc --scenario 1     # one scenario
./scripts/chaos.sh grpc --all            # the suite, sequentially, with cool-down
./scripts/chaos.sh reset                 # clear every injected fault
```

Every toggle is guarded by profile (`local`, `dev`) and by the `ADMIN` role, and is disabled under
`prod` — the same guard step 17's HTTP failure endpoints use. A chaos endpoint reachable in
production is not a learning tool; it is a vulnerability.
