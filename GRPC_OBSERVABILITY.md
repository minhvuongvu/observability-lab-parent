# gRPC Observability

How gRPC traffic is logged, measured, traced and profiled — and how it joins the four signals this
system already produces, rather than becoming a fifth thing to look at.

The general observability design is [docs/Observability.md](docs/Observability.md). This document
covers only what gRPC adds or changes.

---

## 0. The principle

> A new protocol must not introduce a new vocabulary.

`service`, `environment` and `version` already tag every metric, every log line, every span and every
profile. gRPC telemetry uses **the same three**, plus gRPC-specific dimensions. The result is that
one Grafana variable filters all four signals across both protocols.

The concrete payoff: "is the slowdown in the public API or in the internal hop" becomes a filter on
`protocol`, not a different dashboard.

---

# 1. Logging

## 1.1 What a gRPC server log line looks like

The schema is [the existing one](docs/Logging.md#1-the-log-record) plus a `grpc_*` group. Field names
stay `snake_case`, matching every other record.

```json
{
  "@timestamp": "2026-07-21T10:00:00.015Z",
  "level": "INFO",
  "logger": "c.o.l.i.grpc.InventoryGrpcService",
  "thread": "grpc-default-executor-3",
  "message": "BatchCheckStock handled 25 sku(s): 22 sufficient, 3 short",

  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "span_id": "00f067aa0ba902b7",
  "request_id": "24b76acffdcb4afdbc89eadf36f8905e",
  "correlation_id": "ORD-20260721-42BDB094",
  "user_id": "manager",
  "service": "inventory-service",
  "environment": "local",
  "version": "1.0.0-SNAPSHOT",

  "protocol": "grpc",
  "grpc_service": "inventory.v1.InventoryService",
  "grpc_method": "BatchCheckStock",
  "grpc_type": "UNARY",
  "grpc_status": "OK",
  "grpc_duration_ms": 15,
  "caller_service": "order-service",
  "peer_address": "10.0.2.167"
}
```

| Field | Source | Why it earns a place |
| --- | --- | --- |
| `protocol` | Interceptor | The one field that lets every existing query separate internal from external traffic. `{service="inventory-service"} \| json \| protocol="grpc"` |
| `grpc_service` | `ServerCall.getMethodDescriptor()` | Fully qualified, **including the version**. This is how a v1 deprecation is measured. |
| `grpc_method` | Same | The operation |
| `grpc_type` | Same | `UNARY`, `SERVER_STREAMING`, `CLIENT_STREAMING`, `BIDI_STREAMING`. A 45-minute duration is alarming for the first and normal for the third |
| `grpc_status` | Status on close | The taxonomy HTTP cannot express — see [GRPC_ERROR_HANDLING.md](GRPC_ERROR_HANDLING.md) |
| `grpc_duration_ms` | Interceptor timer | Server-side handling time, excluding network |
| `caller_service` | `x-caller-service` metadata | **Who is generating this load.** With client-side balancing and one long-lived connection per caller, this is not inferable from the network path |
| `peer_address` | `Grpc.TRANSPORT_ATTR_REMOTE_ADDR` | Which instance, for correlating against a specific pod |

`trace_id` and `span_id` arrive **automatically**. The OpenTelemetry Java agent already instruments
`grpc-java` and already injects those keys into the MDC under exactly these names — the same
`OTEL_INSTRUMENTATION_COMMON_MDC_TRACE_ID_KEY=trace_id` configuration that makes HTTP logs correlate.
No new code.

## 1.2 One line per call, at close

A gRPC call has a lifecycle — open, message(s), half-close, close — and it is tempting to log each
stage. Do not. **One line, when the call closes**, carrying the status and duration.

Per-stage logging multiplies volume by four for unary calls and without bound for streaming ones,
and buys nothing: the interesting facts are all known at close.

Streaming is the exception, and it is handled by counting rather than logging — see §1.5.

## 1.3 What must never be logged

| Never | Why |
| --- | --- |
| **Request or response messages** | Protobuf payloads carry customer identifiers, basket contents and quantities. A log store is not an access-controlled datastore, and "log the request on error" is how PII reaches a search index. |
| **Metadata wholesale** | `authorization` is in there. Dumping the metadata map logs a usable bearer token, with a 7-day retention. |
| **Stack traces for expected statuses** | `NOT_FOUND` for an untracked SKU is an answer, not a fault. A stack trace makes ordinary operation look like a bug and buries real ones. |
| **The proto descriptor or schema** | Large, static, and already in the repository. |

What to log **instead** of the payload:

```java
// Wrong: logs the basket
log.info("BatchCheckStock request: {}", request);

// Right: shape, not content
log.info("BatchCheckStock handled {} sku(s): {} sufficient, {} short",
        request.getItemsCount(), sufficient, shortages);
```

Counts, outcomes and identifiers are almost always what an investigation actually needs. When the
payload is genuinely required, the trace carries the correlation id — and the order it refers to is
in the database, behind proper access control.

**If a payload must ever be logged**, it goes through the existing
[`Masking`](services/shared-library/src/main/java/com/observability/lab/shared/util/Masking.java)
utility and only at `DEBUG`, never enabled by default.

## 1.4 Log levels by status

Getting this wrong is how an alert channel becomes noise.

| Status | Level | Reasoning |
| --- | --- | --- |
| `OK` | `INFO` | The one line per call |
| `NOT_FOUND`, `ALREADY_EXISTS`, `FAILED_PRECONDITION` | `INFO` | Business answers. The system is working |
| `INVALID_ARGUMENT`, `PERMISSION_DENIED`, `UNAUTHENTICATED` | `WARN` | The **caller** is wrong. Actionable, but not by the on-call engineer for this service |
| `RESOURCE_EXHAUSTED` | `WARN` | Shedding load deliberately. Expected under pressure; alarming if sustained |
| `DEADLINE_EXCEEDED` | `WARN` | Often the caller's budget, not this service's fault. Escalates via metrics if the rate rises |
| `UNAVAILABLE` | `WARN` on the client, `ERROR` on the server if self-inflicted | A dependency is down |
| `INTERNAL`, `DATA_LOSS`, `UNKNOWN` | `ERROR` + stack trace | A bug. The only statuses that deserve a trace |

This mirrors the existing HTTP policy exactly: a rejected order logs at `INFO`, and only genuine
faults log at `ERROR`. One rule, two protocols.

## 1.5 Sampling

At lab volume everything is logged. The strategy matters at scale, and the design accounts for it:

| Traffic | Strategy |
| --- | --- |
| Errors (`INTERNAL`, `UNAVAILABLE`, `DATA_LOSS`) | **Never sampled.** They are rare and each one matters |
| Business statuses (`NOT_FOUND`, `FAILED_PRECONDITION`) | Full, until volume forces otherwise |
| `OK` unary calls | Head-based sampling at high volume, at the **same rate as the trace sampler** so a sampled log has a sampled trace |
| Streaming message events | Never logged individually. Counted as metrics; one line at open and one at close |
| Health checks (`grpc.health.v1.Health/Check`) | **Excluded entirely.** Kubernetes or Consul probing every few seconds would otherwise dominate the log volume with nothing of interest |

That last exclusion is not an optimisation. On a service probed every 10 seconds by three checkers,
health checks are the single largest log producer, and every line of it is noise.

---

# 2. Metrics

## 2.1 The metric set

Micrometer's gRPC instrumentation, with the platform's common tags applied by the existing
`MetricsAutoConfiguration` — so `service`, `environment` and `version` arrive without new code.

### Server

| Metric | Type | Tags |
| --- | --- | --- |
| `grpc_server_requests_total` | Counter | `grpc_service`, `grpc_method`, `grpc_type`, `grpc_status`, `caller_service` |
| `grpc_server_request_duration_seconds` | Timer (**histogram**) | `grpc_service`, `grpc_method`, `grpc_type`, `grpc_status` |
| `grpc_server_handled_total` | Counter | Terminal status per call |
| `grpc_server_messages_received_total` | Counter | Streaming throughput inbound |
| `grpc_server_messages_sent_total` | Counter | Streaming throughput outbound |
| `grpc_server_active_calls` | Gauge | In-flight calls. Saturation |

### Client

| Metric | Type | Tags |
| --- | --- | --- |
| `grpc_client_requests_total` | Counter | `grpc_service`, `grpc_method`, `grpc_status` |
| `grpc_client_request_duration_seconds` | Timer (**histogram**) | Includes network and queueing — the number the caller actually experiences |
| `grpc_client_channel_state` | Gauge | `READY`, `CONNECTING`, `TRANSIENT_FAILURE`, `IDLE` |
| `grpc_client_active_calls` | Gauge | Per channel |

**Server and client duration are both needed and they are different numbers.** The gap between them
is network, queueing and connection establishment. A client p99 of 400 ms against a server p99 of
15 ms is not a slow service — it is a saturated channel or a connection storm, and only having both
distinguishes them.

### Cardinality

`grpc_method` has as many values as the service has RPCs — five. `grpc_status` has seventeen.
`caller_service` has as many values as there are callers. All bounded.

**Never a label:** `product_sku`, `order_number`, `event_id`, `peer_address`. The same rule as
everywhere else in this system — those are log fields. `peer_address` is logged but not tagged,
because in a scaled deployment it is unbounded.

## 2.2 Histograms, and why

`grpc_server_request_duration_seconds` and its client counterpart are added to the existing histogram
allow-list in `MetricsAutoConfiguration`, joining `http.server.requests` and `lab.*`.

The reasoning is the one already documented in [docs/Metrics.md](docs/Metrics.md#3-histograms-and-percentiles):
a mean latency cannot express "one call in a hundred takes four seconds", and a **pre-computed**
percentile cannot be aggregated across instances. Buckets can.

For gRPC there is a second reason. The deadline is a hard boundary — an RPC either completes inside it
or returns `DEADLINE_EXCEEDED`. Knowing what fraction of calls land in the bucket just below the
deadline is how you see a timeout coming before it starts firing.

## 2.3 RED — the request-level view

For any request-serving component, three signals. gRPC maps onto them cleanly.

### Rate

```promql
sum by (grpc_service, grpc_method) (
  rate(grpc_server_requests_total[$__rate_interval])
)
```

### Errors

Error rate as a **ratio**, and — critically — errors defined as *faults*, not as *non-OK*:

```promql
sum(rate(grpc_server_requests_total{
  grpc_status=~"INTERNAL|UNKNOWN|DATA_LOSS|UNAVAILABLE"
}[$__rate_interval]))
/
clamp_min(sum(rate(grpc_server_requests_total[$__rate_interval])), 0.001)
```

`NOT_FOUND` and `FAILED_PRECONDITION` are **deliberately excluded**. An untracked SKU is an answer.
Counting business outcomes as errors makes the error rate track catalogue quality instead of service
health, and the alert built on it becomes something people mute.

### Duration

```promql
histogram_quantile(0.99,
  sum by (grpc_method, le) (
    rate(grpc_server_request_duration_seconds_bucket[$__rate_interval])
  )
)
```

## 2.4 USE — the resource-level view

RED describes the work. USE describes the machine doing it. For gRPC the saturation signals are the
ones that differ most from HTTP.

### Utilisation

| Resource | Metric | Note |
| --- | --- | --- |
| CPU | `process_cpu_usage{service="inventory-service"}` | Already collected. Protobuf decoding is CPU work, unlike JSON's allocation-heavy profile |
| Memory | `jvm_memory_used_bytes{area="heap"}` | Already collected |
| Network | `grpc_server_messages_received_total` × message size | HTTP/2 multiplexing means connection count is a poor proxy for throughput |

### Saturation — the gRPC-specific ones

| Resource | Metric | Why it matters here |
| --- | --- | --- |
| **gRPC executor pool** | `executor_active_threads{name="grpc-executor"}` vs `executor_pool_max_threads` | **The most important saturation signal.** gRPC dispatches onto its own executor, separate from Tomcat's. A pool exhausted here queues RPCs while the HTTP thread pool sits idle and every JVM metric looks healthy |
| In-flight calls | `grpc_server_active_calls` | Rising with flat throughput means calls are taking longer |
| Connection pool (DB) | `hikaricp_connections_pending` | Already collected. gRPC's higher throughput can expose a pool sized for REST |
| HTTP/2 flow-control | `grpc_server_messages_received_total` rate flattening under load | Back-pressure working — informative, not alarming |
| Streaming subscriptions | `grpc_server_active_calls{grpc_type="SERVER_STREAMING"}` | Each is a held resource. Leaked subscriptions accumulate silently |

**The executor pool row deserves emphasis.** It is the failure this system is most likely to hit and
the hardest to diagnose without the metric: requests are slow, CPU is low, heap is fine, the database
is idle, and Tomcat's thread pool is empty — because the queue is in front of a pool nothing else
measures.

### Errors

| Kind | Metric |
| --- | --- |
| Deadline exceeded | `grpc_server_requests_total{grpc_status="DEADLINE_EXCEEDED"}` |
| Connection failures | `grpc_client_channel_state{state="TRANSIENT_FAILURE"}` |
| Load shed | `grpc_server_requests_total{grpc_status="RESOURCE_EXHAUSTED"}` |

## 2.5 Alerts

Added to [`alerts-grpc.yml`](infrastructure/prometheus/rules/alerts-grpc.yml), following the existing
rule: few, and each one actionable.

```yaml
- alert: GrpcHighFaultRate
  expr: |
    sum by (grpc_service) (rate(grpc_server_requests_total{
      grpc_status=~"INTERNAL|UNKNOWN|DATA_LOSS"}[5m]))
    / clamp_min(sum by (grpc_service) (rate(grpc_server_requests_total[5m])), 0.001) > 0.01
  for: 5m
  labels: { severity: critical }
  annotations:
    summary: "{{ $labels.grpc_service }} is faulting on more than 1% of calls"

- alert: GrpcDeadlineExceededRising
  # The caller gave up. Either this service got slower or the deadline is too tight —
  # the client/server duration gap says which.
  expr: |
    sum by (grpc_method) (rate(grpc_server_requests_total{
      grpc_status="DEADLINE_EXCEEDED"}[5m])) > 0.1
  for: 5m
  labels: { severity: warning }

- alert: GrpcExecutorSaturated
  # The failure with no other symptom: slow calls, idle CPU, healthy heap.
  expr: |
    executor_active_threads{name="grpc-executor"}
    / clamp_min(executor_pool_max_threads{name="grpc-executor"}, 1) > 0.9
  for: 3m
  labels: { severity: warning }

- alert: GrpcChannelNotReady
  # A client that cannot reach the server at all. Fires before users notice.
  expr: grpc_client_channel_state{state="TRANSIENT_FAILURE"} == 1
  for: 2m
  labels: { severity: warning }
```

---

# 3. Distributed tracing

## 3.1 Propagation: HTTP header → gRPC metadata

The W3C trace context crosses the protocol boundary unchanged in *value* and changed in *carrier*.

```
Client
  trace_id = 4bf92f35...
        │
        │  HTTP header:  traceparent: 00-4bf92f35...-a1b2c3d4-01
        ▼
Kong ──► Order Service
  span_id = a1b2c3d4  (SERVER span)
        │
        │  gRPC metadata:  traceparent: 00-4bf92f35...-b5c6d7e8-01
        │                  ^^^^^^^^^^ same key, HTTP/2 header frame
        ▼
Inventory Service
  span_id = f9e8d7c6  (SERVER span, parent = b5c6d7e8)
        │
        ▼
Oracle
  span_id = 11223344  (CLIENT span)
```

**gRPC metadata *is* HTTP/2 headers.** The `traceparent` key is identical; only the framing differs.
This is why W3C Trace Context works across the boundary with no translation layer — and why the OTel
agent needs no configuration to do it.

### The span chain for one batch check

| Span | Service | Kind | Parent |
| --- | --- | --- | --- |
| `POST /api/v1/orders/availability` | order-service | SERVER | Kong (or none) |
| `inventory.v1.InventoryService/BatchCheckStock` | order-service | **CLIENT** | the SERVER span above |
| `inventory.v1.InventoryService/BatchCheckStock` | inventory-service | **SERVER** | the CLIENT span above |
| `MGET stock:*` | inventory-service | CLIENT | the SERVER span |
| `SELECT inventorydb` | inventory-service | CLIENT | the SERVER span |

The CLIENT/SERVER pair around the network hop is what makes the network cost visible: subtract the
server span's duration from the client span's and the remainder is transport, queueing and
serialisation.

## 3.2 Span attributes

Semantic-convention attributes come from the agent. Business attributes are added through the
existing [`Spans`](services/shared-library/src/main/java/com/observability/lab/shared/tracing/Spans.java)
helper, exactly as for HTTP.

| Attribute | Source |
| --- | --- |
| `rpc.system = grpc` | Agent |
| `rpc.service = inventory.v1.InventoryService` | Agent |
| `rpc.method = BatchCheckStock` | Agent |
| `rpc.grpc.status_code = 0` | Agent |
| `server.address`, `server.port` | Agent |
| `order.number`, `order.line_count` | `Spans.attribute(...)` |
| `outcome` | `Spans.attribute(...)` |

## 3.3 Span status — the rule that keeps error rates meaningful

Only genuine faults set `StatusCode.ERROR`:

| gRPC status | Span status |
| --- | --- |
| `OK` | `UNSET` (or `OK`) |
| `NOT_FOUND`, `ALREADY_EXISTS`, `FAILED_PRECONDITION` | **`UNSET`** — a business answer |
| `INVALID_ARGUMENT`, `PERMISSION_DENIED` | `ERROR` on the **client** span (the caller is wrong), `UNSET` on the server |
| `DEADLINE_EXCEEDED`, `UNAVAILABLE`, `RESOURCE_EXHAUSTED` | `ERROR` |
| `INTERNAL`, `UNKNOWN`, `DATA_LOSS` | `ERROR` + `recordException` |

This is the same policy the existing code follows for business refusals, stated in
[docs/Tracing.md](docs/Tracing.md#3-manual-instrumentation-meaning-not-structure): a rejected order
must not appear in the error rate, or the error rate stops meaning anything.

## 3.4 Streaming RPCs break the unary model

A span from stream-open to stream-close is technically correct and operationally useless. A
`WatchStockLevels` span open for 45 minutes tells you nothing, and it will not appear in Tempo until
it closes — so an open stream is invisible for its entire lifetime, which is exactly when you want to
see it.

The treatment:

| Aspect | Approach |
| --- | --- |
| The stream itself | One long span, with **events** at open, first message and close |
| Individual messages | **Not spans.** Counted via `grpc_server_messages_sent_total` |
| Per-message work | A short child span *only* when a message triggers real work — a database write, not a cache read |
| Long-lived streams | An event every N messages, so progress is visible before close |
| Cancellation | An event, with the reason |

**The general rule:** trace the *work*, count the *volume*. A stream carrying 10,000 messages should
produce a handful of spans and one counter that reads 10,000 — not 10,000 spans that overwhelm the
backend and answer nothing a counter would not.

## 3.5 Sampling

`parentbased_always_on` in the lab, as today. Two properties worth stating:

- **Parent-based means the decision propagates.** A trace sampled at the Order Service is sampled at
  the Inventory Service, because the sampling bit travels in `traceparent`. Independent decisions per
  service produce half-traces, which are worse than none.
- **Health checks must be excluded** before any sampling ratio is considered. At one probe per 10
  seconds per checker they dominate the trace volume and are never the trace anyone wants.

---

# 4. Profiling

Pyroscope needs no gRPC-specific configuration — async-profiler samples the JVM, and gRPC handler
frames appear like any other. Two things are worth knowing:

- **`itimer` is the right event here.** A gRPC handler blocked on Oracle burns no CPU. The lab already
  uses `itimer` rather than `cpu` precisely so blocked time is attributed; see
  [docs/Profiling.md](docs/Profiling.md#2-the-four-profile-types).
- **The trace→profile link works across the protocol boundary.** Pyroscope's OTel extension stamps the
  active span id onto profiling labels, so a slow `BatchCheckStock` **server span** leads to the flame
  graph of the CPU that span burned. Protobuf decode cost — if it ever becomes significant — shows up
  there, in `protobuf` frames, rather than being invisible inside "request handling".

---

# 5. Dashboard

A **gRPC — Service Communication** dashboard joins the existing ten, generated by the same
[`generate.py`](infrastructure/grafana/dashboards/generate.py) so it inherits the shared conventions:
one unit per panel, reserved status colours, percentiles from buckets, `or vector(0)` so a healthy
system renders zero.

| Row | Panels |
| --- | --- |
| **Headline** | Call rate · Fault ratio · p99 (server) · Active calls |
| **RED** | Rate by method · Status distribution (stacked, status colours) · Latency percentiles |
| **Client vs server** | Both durations on one panel — *same unit*, and the gap is the point · Channel state |
| **Saturation** | gRPC executor pool utilisation · Active calls by type · DB pool pending |
| **Streaming** | Messages sent/received per second · Active streams by method |
| **Contract** | Call rate by `grpc_service`, i.e. **by version** — the panel that says whether v1 can be retired |
| **Protocol comparison** | The same logical operation over REST and gRPC: latency percentiles, side by side |

The last row is the one that justifies running both. It is also the only panel in the system that
deliberately puts two protocols on one axis — legitimate because they share a unit (seconds) and
comparison is the entire purpose.

---

# 6. Troubleshooting

| Symptom | First look | Then |
| --- | --- | --- |
| gRPC latency up, HTTP flat | gRPC dashboard → client vs server duration | Gap large ⇒ channel/network. Gap small ⇒ Traces → slowest spans inside the handler |
| `DEADLINE_EXCEEDED` rising | Server p99 vs the configured deadline | Server fast ⇒ the deadline is too tight, or the channel is queueing. Server slow ⇒ Profiles → CPU |
| Slow calls, everything green | **gRPC executor pool utilisation** | The classic invisible saturation — see §2.4 |
| `UNAVAILABLE` from the client | `grpc_client_channel_state` | `TRANSIENT_FAILURE` ⇒ Consul catalog: are instances passing? |
| One instance hot, others idle | Channel load-balancing policy | Almost always `pick_first` instead of `round_robin`, or an L4 proxy in the path |
| Trace stops at the gRPC boundary | Is the OTel agent attached to **both** services? | Then check that no custom interceptor is replacing rather than appending metadata |
| Fault rate up, users unaffected | `grpc_status` breakdown | Usually a business status miscategorised as a fault — fix the mapping, not the alert |

## Reproducing each of these deliberately

Every symptom above has a matching chaos scenario in
[GRPC_FAILURE_SIMULATION.md](GRPC_FAILURE_SIMULATION.md). Observability that has never been tested
against a real failure is a guess.
