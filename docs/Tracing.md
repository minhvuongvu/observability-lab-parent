# Tracing

Step 12 adds the third signal. Logs describe one event; metrics describe all of them in aggregate;
a trace describes **one request's path through the whole system**, with the time spent at each hop.
It is the signal that answers "why was *that* request slow", which neither of the others can.

---

## 1. Instrumentation: an agent, not a library

The OpenTelemetry Java agent is attached with `-javaagent` and rewrites bytecode at class-load time.
Nothing in the application depends on it, and no module compiles against it.

That is a deliberate choice over a Spring-native tracing library, and the reason is coverage:

| Instrumented automatically | Notes |
| --- | --- |
| Spring MVC | server spans, one per request |
| JDBC | **PostgreSQL and Oracle alike** — statement text, table, operation |
| Kafka producer and consumer | including context propagation through record headers |
| Lettuce (Redis) | one span per command |
| Feign / HTTP client | outbound calls, with the traceparent injected |
| MinIO | its HTTP client, so object storage is on the waterfall |
| Scheduled methods | the outbox relay pass |

JDBC and the object-storage client in particular have no Spring-native instrumentation to switch on.
Getting Oracle statements onto a waterfall with a library would mean wrapping the `DataSource` by
hand; with the agent it is free.

```bash
./scripts/otel-agent.sh          # resolves the pinned agent into tools/
./scripts/run-service.sh order-service          # attaches it automatically
OTEL_SDK_DISABLED=true ./scripts/run-service.sh order-service    # without it
```

The agent is fetched **through Maven**, not curl: the version is pinned once in the parent POM, the
download is checksummed by the resolver, and `tools/` is git-ignored because a 24 MB binary that can
be reproduced exactly from a coordinate does not belong in the repository.

### The one code change the agent forced

`CorrelationFilter` used to read `trace_id` from the inbound `traceparent` header. With the agent
attached that is wrong: the agent has already started a real server span by the time the filter runs,
and the header describes the *caller's* span, not this one. Writing the caller's span id onto every
log line would silently break the link between a log and its trace.

The filter now prefers `Span.current().getSpanContext()` when it is valid, and falls back to the
header only when nothing is tracing — so the log schema stays populated in a plain `java -jar` run.

---

## 2. Where traces go

```
order-service ─┐
               ├─OTLP─> OpenTelemetry Collector ─┬─OTLP──> Tempo   ──> Grafana
inventory-svc ─┘                                 ├─OTLP──> Jaeger
                                                 └─Zipkin> Zipkin
```

The services export to **one** endpoint. That is the entire argument for a collector: sending the
same spans to three backends otherwise means three exporters configured in every service, and
changing a backend means redeploying the application. Here it is a change to one file.

The collector also absorbs the format difference — the services speak OTLP and nothing else, while
Zipkin wants its own JSON. Translation belongs outside the application.

| Store | Why it is here |
| --- | --- |
| **Tempo** | Indexes only the trace id, like Loki indexes only labels. Cheap storage; retrieval by id is the fast path, which is exactly what the log→trace link uses. Grafana links to this one. |
| **Jaeger** | Speaks OTLP natively. Better trace-comparison and dependency views; heavier storage. |
| **Zipkin** | Predates OpenTelemetry and has its own model, so some attributes look different here for the very same span. Seeing that is more instructive than reading about it. |

All three receive identical input, so they can be compared on the same trace rather than on their
documentation.

Two lab simplifications worth naming: Jaeger and Zipkin both use **in-memory** storage, so a restart
loses their traces — in production these would be Cassandra, Elasticsearch or OpenSearch. And the
sampler is `parentbased_always_on`, which is right for a lab and wrong at real volume, where a
sampling strategy goes instead.

---

## 3. Manual instrumentation: meaning, not structure

The agent produces a structurally complete trace on its own. What it cannot know is what any of it
*means* — that a span belongs to order `ORD-…`, that a reservation was refused for a shortage rather
than a fault. Supplying that is the only instrumentation worth writing by hand, and it lives in
[`Spans`](../services/shared-library/src/main/java/com/observability/lab/shared/tracing/Spans.java).

### Attributes

```
order.number = ORD-20260721-42BDB094
order.customer_id = C-TR
order.currency = EUR
order.total_amount = 19.98
order.line_count = 1
outcome = reserved
```

Which makes this possible — find the trace for a specific order, without knowing its trace id:

```
{ .order.number = "ORD-20260721-42BDB094" }
```

### Events

An event, not a child span, when the thing being recorded has no duration: `order.settled`,
`stock.reservation.rejected`, `order.settlement.redelivery`. A zero-length child span is noise on a
waterfall; an event sits on the timeline where it happened and carries its own attributes.

### Status and exceptions

`Spans.failed(...)` sets `StatusCode.ERROR` and calls `recordException`, which captures the type,
message and stack trace as a span event.

Note what is deliberately **absent**: any way to mark a business refusal as an error. A rejected
order is the system working correctly, and setting an error status on it would put ordinary operation
into the error rate that every alert is built on. Refusals get an `outcome` attribute and an event;
only genuine faults get an error status.

### Links — and why the outbox needs one

This is the interesting one. The transactional outbox means the Kafka publish happens **minutes after
the request that produced it**, on a scheduler thread. Parenting the publish to the original request
would produce a trace with a minutes-long gap and a parent that finished before its child began.

A **link** records the same causality without distorting either timeline. It needs the specific span,
not just the trace, which is why `outbox_events` stores both `trace_id`
([V3](../services/order-service/src/main/resources/db/migration/V3__outbox_correlation.sql)) and
`span_id` ([V4](../services/order-service/src/main/resources/db/migration/V4__outbox_span_id.sql)),
captured on the request thread and replayed by the relay:

```java
var origin = Spans.remoteContext(event.getTraceId(), event.getSpanId());
if (origin.isValid()) {
    Span.current().addLink(origin);
}
```

So one order produces **two traces**, joined by a link rather than fused into one dishonest waterfall:

| Trace | Contains |
| --- | --- |
| the HTTP request | MVC server span, JPA, PostgreSQL inserts, outbox write, MinIO upload |
| the relay pass | scheduled span → Kafka producer → Inventory consumer → Oracle → settlement back |

---

## 4. Correlation between signals

Every link is configured in both directions, because an id copied between two browser tabs is a
workflow people abandon during an incident.

| From | To | How |
| --- | --- | --- |
| Log line | Trace | Loki derived field on `trace_id` → Tempo |
| Trace | Logs | `tracesToLogsV2`, filtered by trace id and service |
| Trace | Metrics | `tracesToMetrics`, request rate for the span's service |

That works because `trace_id` is in the MDC — the agent writes it there under the *same key* this
platform's log schema already used (`OTEL_INSTRUMENTATION_COMMON_MDC_TRACE_ID_KEY=trace_id`), so no
translation was needed.

## 5. Metrics derived from spans

Tempo's metrics generator produces RED metrics and a **service graph** from the spans passing through
it and remote-writes them to Prometheus:

```
traces_spanmetrics_calls_total, traces_spanmetrics_latency_*
traces_service_graph_request_total, traces_service_graph_request_failed_total
```

This is genuinely useful and slightly counter-intuitive: service-to-service metrics exist without
*either* service having been instrumented to produce them, because the spans already describe every
call. The observed graph is:

```
user              -> order-service
user              -> inventory-service
order-service     -> inventory-service     (order-created)
inventory-service -> order-service         (inventory-updated)
```

Prometheus needs `--web.enable-remote-write-receiver` for this; without it Tempo logs
`404 remote write receiver needs to be enabled` and the service-graph panels stay empty.

---

## 6. Verify it

```bash
TOKEN=$(./scripts/token.sh manager | tail -1)
curl -s -X POST http://localhost:8081/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"customerId":"C-1","currency":"EUR","items":[{"productSku":"SKU-1","quantity":1,"unitPrice":"9.99"}]}'

# The trace id the agent generated, straight from the log line
TID=$(tail -100 logs/order-service.json | grep 'Order accepted' | tail -1 \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["trace_id"])')

# The same trace, from all three stores
curl -s "http://localhost:3200/api/traces/$TID"      # Tempo
curl -s "http://localhost:16686/api/traces/$TID"     # Jaeger
curl -s "http://localhost:9411/api/v2/trace/$TID"    # Zipkin

# Find traces by business attribute, no id needed
curl -s -G http://localhost:3200/api/search \
  --data-urlencode 'q={ .order.number = "ORD-..." }'

# What the agent instrumented
curl -s http://localhost:3200/api/search/tag/db.system/values   # oracle, postgresql, redis
```

| UI | Address |
| --- | --- |
| Grafana (Explore → Tempo) | <http://localhost:3000> |
| Jaeger | <http://localhost:16686> |
| Zipkin | <http://localhost:9411> |
| Tempo API | <http://localhost:3200> |
| Collector zpages | <http://localhost:55679/debug/tracez> |

---

## 7. What this step deliberately leaves out

- **No exemplars.** Linking a Prometheus latency bucket to an example trace needs exemplar support
  wired through the Micrometer registry; the histogram buckets and the traces both exist, so this is
  a connection to add rather than new machinery.
- **Kong and Nginx are not traced.** The edge would need its own instrumentation (Kong's OpenTelemetry
  plugin), which is a gateway change belonging with a revision of step 06. Traces currently start at
  the service, so the `user -> order-service` edge is the client's view, not the gateway's.
- **No tail-based sampling.** The collector supports it and it is the right answer at volume — keep
  every errored or slow trace, sample the rest — but with `always_on` there is nothing yet to sample.
