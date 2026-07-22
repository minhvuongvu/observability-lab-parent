# gRPC

How the Order → Inventory hop is built: the contract, the server, the channel, the reliability policy
and what all of it emits.

This is the implementation. The design that preceded it is in
[GRPC_ENHANCEMENT_ANALYSIS.md](../GRPC_ENHANCEMENT_ANALYSIS.md) (why),
[GRPC_ARCHITECTURE.md](../GRPC_ARCHITECTURE.md) (what),
[GRPC_PROTO_DESIGN.md](../GRPC_PROTO_DESIGN.md) (the contract),
[GRPC_ERROR_HANDLING.md](../GRPC_ERROR_HANDLING.md) (failure) and
[GRPC_OBSERVABILITY.md](../GRPC_OBSERVABILITY.md) (signals). This document says where each decision
actually lives in the code, and how to see it working.

---

## 1. What was added, and what was not

REST and Kafka are untouched. gRPC is a **third** transport, not a replacement:

| Hop | Protocol | Why |
| --- | --- | --- |
| Client → Nginx → Kong → service | REST / JSON | Public, gateway-routed, universally consumable |
| **Order → Inventory (synchronous)** | **gRPC / HTTP/2 + protobuf** | Internal, latency-sensitive, contract-enforced, streaming-capable |
| Order ⇄ Kafka ⇄ Inventory | Kafka / JSON | Must survive the other service being down |

> **REST at the edge. gRPC between services when the caller must wait. Kafka when it must not.**

The gRPC port is **not** routed through Kong. That is a deliberate property: the gateway's plugin
stack is HTTP-native, and publishing an internal contract at the edge is how it stops being internal.

---

## 2. The contract

One file, at the repository root, owned by the provider:

```
proto/inventory/v1/inventory_service.proto     package inventory.v1
```

Both modules generate from **that one file** at build time — the provider its server base class, the
consumer its client stubs — via `protobuf-maven-plugin`, configured once in the parent POM. Generated
sources land in `target/generated-sources/protobuf` and are **never committed**: a committed stub is
one that can drift from the `.proto` that supposedly produced it.

That single source is the point. It is what makes the class of defect the REST path is still capable
of — a response envelope decoded into a record full of zeros, indistinguishable from a genuine zero —
impossible here.

| Rule | Where it is enforced |
| --- | --- |
| The version is a directory *and* a package (`inventory/v1` ↔ `inventory.v1`) | The file layout |
| Java package distinct from the proto package | `option java_package` |
| Field numbers are permanent; enums are append-only | Review, and `buf breaking` in CI (step 18) |
| Consumers must handle unknown enum values | `default ->` arms in `InventoryGrpcClient` |

The last row is worth its own line. A `switch` over a proto enum without a `default` compiles cleanly
and fails the first time the server is upgraded. `InventoryGrpcClient.toReservation` has one, and it
falls through to the asynchronous path rather than guessing.

### 2.1 The five RPCs

| RPC | Type | Deadline | Purpose |
| --- | --- | --- | --- |
| `CheckStock` | Unary | 200 ms | One product. Advisory, cache-backed |
| `BatchCheckStock` | Unary | 300 ms | **The N+1 fix.** One round trip and one `IN` query for a whole basket |
| `ReserveStock` | Unary | 200 ms | Express reservation, racing the Kafka path under a tight budget |
| `WatchStockLevels` | Server streaming | none | Live levels for a watchlist |
| `BulkAdjustStock` | Client streaming | 5 min | Warehouse reconciliation, applied in batches |

---

## 3. Where the code is

### Shared library — behaviour identical on both sides

`com.observability.lab.shared.grpc`, wired by `GrpcAutoConfiguration`:

| Class | Responsibility |
| --- | --- |
| `GrpcCorrelationServerInterceptor` | Reads and sanitises metadata, publishes it into the gRPC `Context`, mirrors it into the MDC around **every** listener callback |
| `GrpcMetricsServerInterceptor` | RED and USE meters, tagged in the platform's existing vocabulary |
| `GrpcLoggingServerInterceptor` | One line per call, at close, at the level the status deserves |
| `GrpcAuthenticationServerInterceptor` | Verifies the relayed bearer token with the *same* `JwtDecoder` the HTTP resource server uses |
| `GrpcExceptionServerInterceptor` | Domain exception → status, in one place |
| `GrpcStatusMapper` | The taxonomy. Driven by `ErrorCategory`, so HTTP and gRPC cannot disagree |
| `GrpcCorrelationClientInterceptor` | Writes the metadata, relays the token, appends rather than replaces |
| `GrpcMetricsClientInterceptor` | Client-side duration — the number the caller actually experienced |
| `GrpcDeadlineClientInterceptor` | Backstop for a call issued without a deadline |

**Interceptor order is load-bearing**, and `GrpcServerInterceptorChain` is where it is stated:

```
correlation → metrics → logging → authentication → exception mapping → handler
```

Correlation outermost, so a failure logged during authentication already has a trace id.
Authentication *inside* logging and metrics, so a rejected call still appears on the dashboards.
Exception mapping innermost, so the status the logging interceptor records is the mapped one rather
than gRPC's `UNKNOWN` fallback.

### Inventory Service — the provider

| Class | Responsibility |
| --- | --- |
| `grpc/InventoryGrpcService` | The five RPCs. Translates and delegates; every stock rule stays in `StockApplicationService` |
| `grpc/StockWatchRegistry` | Fans committed changes out to open subscriptions |
| `config/GrpcServerConfiguration` | Port, handler pool, health service, reflection |
| `config/GrpcServerLifecycle` | Binds late, drains early |

### Order Service — the consumer

| Class | Responsibility |
| --- | --- |
| `infrastructure/grpc/InventoryChannelConfiguration` | The channel, the service config (retry + budget), the circuit breaker |
| `infrastructure/grpc/DiscoveryClientNameResolverProvider` | Resolves `consul:///inventory-service` to gRPC endpoints |
| `infrastructure/grpc/InventoryGrpcClient` | Deadlines, breaker, and a defined fallback per call |
| `application/ExpressReservationListener` | Races the Kafka path after commit, and loses gracefully |

---

## 4. Discovery and load balancing

**This is the operational trap in gRPC, and it catches almost everyone once.**

A gRPC channel opens one long-lived HTTP/2 connection and multiplexes every RPC over it. A layer-4
load balancer therefore balances *connections*, not requests — the connection is established once and
pinned, so one instance receives every RPC while its peers idle. Scaling out changes nothing, and the
symptom looks like a scheduling problem rather than a protocol one.

So the channel resolves and balances itself:

```
Order Service ──┬──► inventory #1 :9082   ~33%
  round_robin   ├──► inventory #2 :9082   ~33%
                └──► inventory #3 :9082   ~33%
       │
       └── DiscoveryClientNameResolverProvider → Consul (passing instances only)
```

- The Inventory Service registers `grpc-port` as Consul **metadata** and tags itself `grpc`. Consul
  registers one port per service, so the second travels as metadata; the HTTP health check remains
  authoritative for both, because a process that cannot serve HTTP cannot serve gRPC either.
- The resolver keeps only instances that advertise the metadata, so a rolling upgrade is a non-event
  rather than a burst of connection refusals.
- `defaultLoadBalancingPolicy("round_robin")` is set **explicitly**. The gRPC default is
  `pick_first`, which uses one instance and ignores the rest while looking correctly configured.

In Kubernetes the same trap is differently shaped: a `ClusterIP` Service is L4, so pods get pinned
the same way. The answers there are a headless Service, a mesh sidecar, or xDS. Consul plays that
role here. The principle is identical: **something must balance at layer 7, and by default nothing
does.**

---

## 5. Reliability

### 5.1 Deadlines, not timeouts

A timeout is local: "I will wait 300 ms." A **deadline is absolute and propagates**: "this call must
complete by 10:00:00.300." It travels in `grpc-timeout` metadata, so each hop sees what *remains*
rather than starting a fresh budget — and a server can decline work whose caller has already given up.

```
Client HTTP request budget        3000 ms
  └─ Order Service handler        1000 ms
      └─ gRPC BatchCheckStock      300 ms
          └─ Oracle query          250 ms
```

Budgets shrink inwards. If a callee's budget exceeded its caller's, the callee's timeout would never
fire and the caller would time out first — losing the more specific error.

`InventoryGrpcService.requireBudget()` is the server side of that: under 50 ms remaining, it returns
`DEADLINE_EXCEEDED` rather than spending an Oracle connection on a result nobody will receive.

### 5.2 Status taxonomy

**The mapping is control flow, not documentation** — the retry policy keys off the status code. A
database timeout reported as `INTERNAL` is not retried and the request fails; reported as
`UNAVAILABLE` it is retried against a healthy instance and succeeds.

`GrpcStatusMapper` derives the status from `ErrorCategory`, which already drives the HTTP status and
the log level. **One exception hierarchy, two transport mappings:**

| Category | HTTP | gRPC |
| --- | --- | --- |
| `VALIDATION` | 400 | `INVALID_ARGUMENT` |
| `AUTHENTICATION` | 401 | `UNAUTHENTICATED` |
| `AUTHORIZATION` | 403 | `PERMISSION_DENIED` |
| `NOT_FOUND` | 404 | `NOT_FOUND` |
| `CONFLICT` | 409 | `ALREADY_EXISTS` |
| `BUSINESS_RULE` | 422 | `FAILED_PRECONDITION` |
| `INTEGRATION` | 502 | `UNAVAILABLE` |
| `TIMEOUT` | 504 | `DEADLINE_EXCEEDED` |
| `TECHNICAL` | 500 | `INTERNAL` |

Plus the data-access rules that the taxonomy exists for: `QueryTimeoutException` and
`CannotAcquireLockException` are **`UNAVAILABLE`, never `INTERNAL`**, and
`OptimisticLockingFailureException` is `ABORTED` — a concurrency conflict, not a fault, and retrying
is exactly right.

Errors travel in the **status channel** with `google.rpc.BadRequest` detail, never in a successful
response. A `status: OK` carrying an `error_message` field means every client must check two places,
and one of them will eventually be forgotten.

### 5.3 Retries

In the channel's service config, so the policy is data rather than a decision scattered across call
sites:

```json
{ "maxAttempts": 3, "initialBackoff": "0.05s", "maxBackoff": "0.5s",
  "backoffMultiplier": 2.0,
  "retryableStatusCodes": ["UNAVAILABLE", "RESOURCE_EXHAUSTED", "ABORTED"] }
```

`DEADLINE_EXCEEDED` is deliberately absent: the budget is already gone, so retrying guarantees
another failure while adding load.

`ReserveStock` mutates state and is still retryable — **not because retrying is harmless, but because
idempotency was designed in.** `event_id` is the same key the Kafka path uses, which is what lets the
two race. Idempotency is what makes retries possible; without it, a retryable status on a mutating
call is a correctness bug.

A **retry budget** (`retryThrottling`, 20%) caps retries as a share of traffic. Without it a
per-attempt policy still permits 3× amplification during a partial outage — the single most common
way a retry policy causes the outage it was defending against.

### 5.4 Circuit breaker

Retries handle a *transient* failure; a breaker handles a *sustained* one.

| Setting | Value |
| --- | --- |
| Sliding window | 20 calls |
| Failure threshold | 50% |
| Slow-call threshold | 80% slower than 250 ms |
| Open duration | 10 s |
| Half-open probes | 3 |
| **Counted as failure** | `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED` |
| **Not counted** | `NOT_FOUND`, `INVALID_ARGUMENT`, `FAILED_PRECONDITION`, `PERMISSION_DENIED` |

The slow-call threshold matters: a dependency answering every call in five seconds is as damaging as
one returning errors, and a pure error-rate breaker never notices.

That last row is the most common misconfiguration in practice, and it fails in the worst direction —
the breaker opens because the *data* was unusual, and a perfectly healthy dependency is cut off. A
catalogue full of untracked SKUs must never trip it.

### 5.5 Fallbacks

**A circuit breaker with no fallback is just a faster failure.**

| Call | Fallback |
| --- | --- |
| `CheckStock` / `BatchCheckStock` | Every line degrades to `known = false`. The UI says "availability unavailable" rather than a confident and wrong "out of stock" |
| `ReserveStock` | Falls through to the Kafka path. The order stays `PENDING`, which is what it would have been had the attempt never been made |

The reservation fallback is the asynchronous design paying off: because the Kafka path is the
invariant rather than a bolt-on, the breaker has somewhere safe to land.

---

## 6. Observability

### 6.1 The principle

> A new protocol must not introduce a new vocabulary.

`service`, `environment` and `version` already tag every metric, log line, span and profile. gRPC
reuses all three and adds `protocol`, so "is the slowdown in the public API or in the internal hop"
is a **filter**, not a different dashboard. The HTTP correlation filter now sets `protocol=http` for
exactly that reason: an absent field cannot be grouped by.

### 6.2 Logs

One line per call, written at close, with the gRPC identity in the MDC for the **whole** call — so an
application log line carries `grpc_method` and `caller_service` too, which is where an investigation
actually looks.

```json
{
  "@timestamp": "2026-07-21T10:00:00.015Z", "level": "INFO",
  "logger": "c.o.l.i.grpc.InventoryGrpcService",
  "message": "BatchCheckStock handled 25 sku(s): 22 sufficient, 3 short",
  "trace_id": "4bf92f35...", "span_id": "00f067aa...",
  "correlation_id": "ORD-20260721-42BDB094", "service": "inventory-service",
  "protocol": "grpc", "grpc_service": "inventory.v1.InventoryService",
  "grpc_method": "BatchCheckStock", "grpc_type": "UNARY",
  "grpc_status": "OK", "grpc_duration_ms": 15,
  "caller_service": "order-service", "peer_address": "10.0.2.167"
}
```

`caller_service` earns its place: with client-side balancing and one long-lived connection per
caller, "who is generating this load" is **not** inferable from the network path.

**Never logged:** request or response messages (protobuf payloads carry basket contents), metadata
wholesale (`authorization` is in there), or stack traces for expected statuses. Health checks are
excluded entirely — on a service probed every ten seconds by three checkers they are the single
largest log producer and every line is noise.

Log levels mirror the HTTP policy exactly: `NOT_FOUND` and `FAILED_PRECONDITION` at `INFO` because
they are answers; `INVALID_ARGUMENT` and `DEADLINE_EXCEEDED` at `WARN`; only `INTERNAL`, `UNKNOWN`
and `DATA_LOSS` at `ERROR`, with a stack trace.

### 6.3 Metrics

| Metric | Type | Key tags |
| --- | --- | --- |
| `grpc_server_requests_total` | Counter | `grpc_service`, `grpc_method`, `grpc_type`, `grpc_status`, `caller_service` |
| `grpc_server_request_duration_seconds` | Histogram | as above, minus caller |
| `grpc_server_handled_total` | Counter | terminal status |
| `grpc_server_messages_{sent,received}_total` | Counter | streaming throughput |
| `grpc_server_active_calls` | Gauge | in flight, by type |
| `grpc_client_requests_total` | Counter | `grpc_service`, `grpc_method`, `grpc_status` |
| `grpc_client_request_duration_seconds` | Histogram | **includes network and queueing** |
| `grpc_client_active_calls` | Gauge | per method |
| `grpc_client_channel_state` | Gauge | `state`, `target` |

**Never a label:** `product_sku`, `order_number`, `event_id`, `peer_address`. Those are log fields —
`peer_address` is logged but not tagged, because in a scaled deployment it is unbounded.

**Server and client duration are both needed and they are different numbers.** The gap is network,
queueing and connection establishment: a client p99 of 400 ms against a server p99 of 15 ms is not a
slow service, it is a saturated channel, and only having both distinguishes them.

Both are added to the histogram allow-list in `MetricsAutoConfiguration`. Beyond the usual argument
against means and pre-computed percentiles, gRPC has a second reason: the deadline is a hard
boundary, so knowing what fraction of calls land in the bucket just below it is how a timeout is seen
coming before it starts firing.

#### Saturation — the one that matters

`executor_active_threads{name="grpc-executor"}` against `executor_pool_max_threads`.

gRPC dispatches onto **its own** executor, separate from Tomcat's. A pool exhausted here queues RPCs
while the HTTP thread pool sits idle and every JVM metric looks healthy: requests are slow, CPU is
low, heap is fine, the database is idle — because the queue is in front of a pool nothing else
measures. `GrpcServerConfiguration` binds that pool to Micrometer under exactly that name, and the
queue is **bounded** so overload surfaces as `RESOURCE_EXHAUSTED` rather than as unbounded latency
and eventually an `OutOfMemoryError`.

### 6.4 Traces

Nothing to configure. **gRPC metadata *is* the HTTP/2 header set**, `traceparent` is the same key,
and the OpenTelemetry agent already instruments `grpc-java`. The chain for one batch check:

| Span | Service | Kind |
| --- | --- | --- |
| `POST /api/v1/orders/availability` | order-service | SERVER |
| `inventory.v1.InventoryService/BatchCheckStock` | order-service | **CLIENT** |
| `inventory.v1.InventoryService/BatchCheckStock` | inventory-service | **SERVER** |
| `SELECT inventorydb` | inventory-service | CLIENT |

The CLIENT/SERVER pair around the network hop is what makes transport cost visible: subtract the
server span from the client span and the remainder is network, queueing and serialisation.

The one thing custom interceptors must not do is **replace** the metadata map instead of appending to
it. That is the single most common way trace propagation is silently disabled, and the symptom — a
trace that stops at the gRPC boundary — looks like an agent problem rather than an application one.

Streaming breaks the unary span model: a `WatchStockLevels` span open for 45 minutes tells you
nothing and does not reach Tempo until it closes, so an open stream is invisible for exactly the
period you want to see it. The treatment is **trace the work, count the volume** — messages are
counters, not spans.

### 6.5 Dashboard

**gRPC — Service Communication** (`lab-grpc`), generated by the same
[`generate.py`](../infrastructure/grafana/dashboards/generate.py) as the other ten, so it inherits
one unit per panel, reserved status colours, percentiles from buckets and `or vector(0)`.

| Row | What it answers |
| --- | --- |
| Headline | Rate, fault ratio, server p99, calls in flight |
| RED | Rate by method, status distribution, latency percentiles |
| Client vs server | Both durations on one axis — same unit, and the gap *is* the point |
| Saturation | The gRPC executor pool, active calls by type, DB pool pressure |
| Streaming | Messages/s, and call rate by `grpc_service` — i.e. **by contract version** |
| Protocol comparison | The same operation over REST and gRPC, side by side |

The version row is how a deprecation finishes: v1 can be retired when its call rate reaches zero, and
not before. A deprecation you cannot measure is one that never ends.

### 6.6 Alerts

Five, in [`alerts-grpc.yml`](../infrastructure/prometheus/rules/alerts-grpc.yml): `GrpcHighFaultRate`,
`GrpcDeadlineExceededRising`, `GrpcExecutorSaturated`, `GrpcChannelNotReady` and
`GrpcCircuitBreakerOpen`. The fault-rate alert counts faults only — `NOT_FOUND` and
`FAILED_PRECONDITION` are excluded, or the alert would track catalogue quality instead of service
health and become something people mute. Step 16 gave all five a severity, a category and a
first-response note, and routed them — see [Alerting.md](Alerting.md).

---

## 7. Trying it

Start the stack and both services:

```bash
./scripts/infra.sh up
./scripts/build.sh
./scripts/infra.sh up      # both services, containerised, on lab-net
                           # inventory: REST :8082, gRPC :9082 — order: REST :8081
```

### The protocol comparison

The same question, both ways, against the same data:

```bash
TOKEN=$(./scripts/token.sh alice)

# gRPC: one round trip, one IN query
curl -s -X POST "http://localhost:8081/api/v1/orders/availability?transport=GRPC" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-1","currency":"EUR","items":[
        {"productSku":"SKU-A","quantity":2,"unitPrice":9.99},
        {"productSku":"SKU-B","quantity":1,"unitPrice":4.50}]}' | jq

# REST: one HTTP request and one point lookup per line
curl -s -X POST "http://localhost:8081/api/v1/orders/availability?transport=REST" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '@basket.json' | jq
```

Both answer identically. The difference is what it cost to ask, and it widens with basket size — the
gRPC cost is flat, the REST cost is linear. Watch the last row of the gRPC dashboard.

### The express reservation

```bash
curl -s -X POST http://localhost:8081/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-1","currency":"EUR","items":[{"productSku":"SKU-A","quantity":1,"unitPrice":9.99}]}'
```

The response is `201 PENDING` as before — the express attempt runs after the transaction commits.
Read the order back a moment later and it is usually already `CONFIRMED`, settled by gRPC rather than
by the Kafka round trip. Stop the Inventory Service and place another: the order is still accepted,
stays `PENDING`, and settles when Inventory returns. **The gRPC path is an optimisation that cannot
change the outcome.**

### With grpcurl

Server reflection is on outside `prod`, so no `.proto` copy is needed:

```bash
grpcurl -plaintext localhost:9082 list
grpcurl -plaintext localhost:9082 describe inventory.v1.InventoryService

grpcurl -plaintext -H "authorization: Bearer $TOKEN" \
  -d '{"product_sku":"SKU-A","requested_quantity":2}' \
  localhost:9082 inventory.v1.InventoryService/CheckStock

# The health service reports the same state as /actuator/health
grpcurl -plaintext localhost:9082 grpc.health.v1.Health/Check
```

Omitting the token yields `UNAUTHENTICATED`: the port authenticates, because the network is not the
control.

---

## 8. Troubleshooting

| Symptom | First look | Then |
| --- | --- | --- |
| gRPC latency up, HTTP flat | gRPC dashboard → client vs server duration | Gap large ⇒ channel or network. Gap small ⇒ Traces → slowest spans inside the handler |
| `DEADLINE_EXCEEDED` rising | Server p99 against the configured deadline | Server fast ⇒ the deadline is too tight, or the channel is queueing. Server slow ⇒ Profiles → CPU |
| **Slow calls, everything green** | **gRPC executor pool utilisation** | The classic invisible saturation — §6.3 |
| `UNAVAILABLE` from the client | `grpc_client_channel_state` | `TRANSIENT_FAILURE` ⇒ check the Consul catalog: are instances passing, and do they advertise `grpc-port`? |
| One instance hot, others idle | Channel load-balancing policy | Almost always `pick_first` instead of `round_robin`, or an L4 proxy in the path |
| Trace stops at the gRPC boundary | Is the OTel agent attached to **both** services? | Then check that no interceptor replaces rather than appends metadata |
| Fault rate up, users unaffected | `grpc_status` breakdown | Usually a business status miscategorised as a fault — fix the mapping, not the alert |
| `UNKNOWN` from any call | The exception mapping | Every `UNKNOWN` is a gap in `GrpcStatusMapper`, not a category of failure |

Each of these has a matching chaos scenario in
[GRPC_FAILURE_SIMULATION.md](../GRPC_FAILURE_SIMULATION.md), which step 17 implements. Observability
that has never been tested against a real failure is a guess.

---

## 9. Configuration

**Provider** — `app.grpc.server.*` in the Inventory Service:

| Property | Default | Note |
| --- | --- | --- |
| `port` | 9082 | `90xx` mirrors the `80xx` HTTP port |
| `bind-address` | `127.0.0.1` | Set to `0.0.0.0` in the container, which is why the port authenticates: loopback was the control, and containerising removed it |
| `executor-threads` | 16 | gRPC's own pool, separate from Tomcat's |
| `executor-queue-capacity` | 256 | Bounded, so overload is `RESOURCE_EXHAUSTED` rather than an OOM |
| `reflection-enabled` | `true`, **`false` under `prod`** | Reflection publishes the whole schema |
| `authentication.enabled` | `true` | Same `JwtDecoder` as the HTTP path |

**Consumer** — `app.grpc.client.inventory.*` in the Order Service: `enabled`,
`express-reservation`, the three deadlines, keepalive and idle timeouts, `max-retry-attempts`,
`retry-budget-ratio`, and the `circuit-breaker.*` block. Setting `enabled: false` removes the channel
and the client; availability then answers over REST, and the service still starts.

---

## 10. What is deliberately absent

| Excluded | Why |
| --- | --- |
| gRPC through Kong | The gateway's plugins are HTTP-native, and an internal contract published at the edge is no longer internal |
| TLS between services | Plaintext everywhere in this lab. A real deployment uses mTLS, which gRPC supports natively and which is where a service mesh takes over |
| Auth tokens or trace ids as proto fields | They belong in metadata, where interceptors handle them uniformly and they never reach a message log |
| Error details in the response body | gRPC has a status channel. Two places to check means one of them gets forgotten |
| Committed generated code | It is build output, and a committed stub drifts from its `.proto` |
