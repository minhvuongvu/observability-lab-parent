# Metrics

Step 11 adds the second telemetry signal. Logs say what happened to *one* request; metrics say what
is happening to *all* of them, cheaply enough to keep forever and to alert on.

---

## 1. The shape of a metric here

Every meter carries `service`, `environment` and `version` — the same three fields every log line
carries, deliberately. A dashboard panel and a log query should filter on the identical vocabulary,
or correlating them during an incident means translating between two.

They are applied as **common tags** by
[`MetricsAutoConfiguration`](../services/shared-library/src/main/java/com/observability/lab/shared/autoconfigure/MetricsAutoConfiguration.java)
in the shared library, not per-meter in each service. Adding them at the registry means meters
registered by a *library* — Hikari's, Kafka's, Tomcat's — carry them too, which no amount of
discipline in application code could achieve.

```
jvm_memory_used_bytes{area="heap",environment="local",id="G1 Eden Space",service="order-service",version="1.0.0-SNAPSHOT"}
lab_orders_settled_total{environment="local",outcome="confirmed",service="order-service",version="1.0.0-SNAPSHOT"}
```

Metrics this platform defines itself are prefixed `lab_`. That namespace is a single answer to "what
did we write, as opposed to what did Spring give us". Names live in
[`MetricTags`](../services/shared-library/src/main/java/com/observability/lab/shared/metrics/MetricTags.java)
and are treated as public API — they end up in PromQL, panels and alert rules, so renaming one breaks
everything built on it.

### Cardinality

Tagged by: `currency`, `outcome`, `service`, `environment`, `version` — each a handful of values.

**Not** tagged by: customer id, order number, SKU. Every distinct tag combination is a separate time
series, so one series per customer is how a metrics backend is brought down. Those are log questions,
and the logs are indexed for them. `MetricTags.PRODUCT_SKU` exists as a constant precisely so its
absence from the meters reads as a decision rather than an oversight.

---

## 2. The five instrument types

Each is used for the thing it is actually for, which is most of what makes a metric useful:

| Instrument | Where | Why that one |
| --- | --- | --- |
| **Counter** | `lab_orders_accepted_total`, `lab_reservations_total` | Monotonic count of events. Rates are derived at query time, not baked in. |
| **Timer** | `lab_reservation_duration_seconds`, `lab_invoice_upload_seconds` | Duration *and* frequency, in one meter. |
| **DistributionSummary** | `lab_order_value_currency` | The distribution of a non-time quantity. |
| **Gauge** | `lab_outbox_pending_events` | A value that goes down as well as up: how many events are owed to Kafka *right now*. |
| **LongTaskTimer** | `lab_outbox_relay_active_seconds` | Duration of work **still in flight**. A plain timer records nothing until the task finishes — precisely wrong for detecting a relay wedged against an unreachable broker. |

Two implementation notes that are easy to get wrong:

- The outbox gauge reads an `AtomicLong` the relay updates, **not** a lambda that queries the
  database. A gauge callback runs on the scrape thread; putting SQL there means `/actuator/prometheus`
  fails whenever the database is slow, which is exactly when it is most needed.
- A meter name may not be registered with two different **tag key sets**. Pre-registering an untagged
  `lab.reservation.duration` in a constructor and then registering it again with an `outcome` tag
  makes the tagged one impossible: Prometheus rejects it with a warning, and the untagged one sits on
  the dashboard reading zero forever. Register once, at the point where the tags are known.

---

## 3. Histograms and percentiles

An average latency is the number that hides every problem worth finding — it cannot express "one
request in a hundred takes four seconds". So the latencies that get alerted on publish **histogram
buckets**:

```
http.server.requests, http.client.requests, lab.*
```

The percentile is then computed **server-side from bucket counts**:

```promql
histogram_quantile(0.99, sum by (service, le) (rate(http_server_requests_seconds_bucket[5m])))
```

That distinction matters more than it looks. A percentile pre-computed inside each process **cannot
be aggregated** — the 99th percentile of three instances is not the average of their three 99th
percentiles — so it becomes meaningless the moment a service is scaled out. Bucket counts add up
correctly. Prometheus drops the pre-computed `*_percentile` series at scrape time for that reason.

The cost is real: each histogram is one series per bucket per tag combination. That is why the
buckets are bounded (5ms–10s) and applied to a short list of meters rather than to every timer.

---

## 4. What is collected

| Area | Metrics | Source |
| --- | --- | --- |
| HTTP | `http_server_requests_seconds_*` by method, uri, status, outcome | Actuator, automatic |
| JVM | heap and non-heap, GC pause, threads, classes | Micrometer binders |
| CPU | `process_cpu_usage`, `system_cpu_usage` | Micrometer binders |
| Connection pool | `hikaricp_connections_{active,idle,pending}` | Hikari, automatic |
| Kafka | producer send rate, consumer records consumed, **lag**, rebalances | client metrics — see below |
| Redis | `lettuce_command_completion_seconds_*` | Lettuce, automatic |
| Business | `lab_orders_*`, `lab_reservations_*`, `lab_outbox_*`, `lab_invoice_*` | [`OrderMetrics`](../services/order-service/src/main/java/com/observability/lab/order/application/OrderMetrics.java), [`StockMetrics`](../services/inventory-service/src/main/java/com/observability/lab/inventory/application/StockMetrics.java) |

**The Kafka trap.** Spring Boot attaches a Micrometer listener to the producer and consumer factories
*it* builds — but declaring a custom `ProducerFactory`/`ConsumerFactory` bean makes that
auto-configuration back off entirely, taking the listener with it. Both services declare custom
factories (to control serialisation), so both must add
`MicrometerProducerListener`/`MicrometerConsumerListener` explicitly. Without it the Kafka client's
own metrics are silently absent, and the gap only surfaces as a permanently empty dashboard panel.

---

## 5. Two stores, one query language

```
services ──> /actuator/prometheus ──> Prometheus ──remote_write──> VictoriaMetrics
                                          │                              │
                                          └──────────> Grafana <─────────┘
```

**Pull, not push.** Prometheus deciding when to scrape means a target that has stopped answering is
itself a signal (`up == 0`). With a push model, a silent target and a healthy-but-idle one are
indistinguishable.

| | Prometheus | VictoriaMetrics |
| --- | --- | --- |
| Role | scrapes, evaluates rules, alerts | long-term store |
| Retention | 24h | 30d |
| Query language | PromQL | PromQL |

Both are provisioned as Grafana datasources and every dashboard has a **Store** variable, so the same
panel can be pointed at either and compared on identical input — which is the point of running two.

---

## 6. Rules

[`lab-alerts.yml`](../infrastructure/prometheus/rules/lab-alerts.yml) holds three recording rules and
six alerts. Deliberately few: a rule that fires on something nobody would act on trains people to
ignore the channel, and an ignored alert channel is worse than none.

The one worth singling out:

```promql
sum(rate(lab_orders_accepted_total[10m])) - sum(rate(lab_orders_settled_total[10m])) > 0.1
```

Orders accepted faster than they are settled. **Every technical metric can be green while this
fires** — nothing is failing, the answer simply never comes back. That is the case business metrics
exist for, and the reason `lab_orders_accepted_total` and `lab_orders_settled_total` are separate
counters rather than one with a status tag.

There is no Alertmanager. These evaluate and show as firing in Prometheus and Grafana, which is
enough to see the mechanics without adding a routing and paging component this step does not call for.

---

## 7. Dashboards

| Dashboard | Contents |
| --- | --- |
| **Business — Order Flow** | Accepted vs settled, settlement outcome, unsettled gap, outbox backlog, reservation latency, order value distribution |
| **Runtime — JVM, HTTP, Pool, Kafka, Redis** | Request rate and latency percentiles, responses by outcome, heap, GC, threads, CPU, pool saturation, Kafka throughput, Redis latency |

Provisioned from [files](../infrastructure/grafana/dashboards/metrics) and read-only in the UI, for
the same reason as the logs dashboard.

Note that rate and latency are **separate panels**. Requests-per-second and seconds are different
units, and a dual-axis chart makes both unreadable — the single most common way a dashboard becomes
decorative.

---

## 8. Verify it

```bash
# The raw exposition
curl -s http://localhost:8081/actuator/prometheus | grep '^lab_'

# Both stores answer the same query with the same number
for ds in http://localhost:9090 http://localhost:8428; do
  curl -s -G "$ds/api/v1/query" --data-urlencode 'query=sum(lab_orders_accepted_total)'
done

# Scrape health — a target that is down is the signal, not an inconvenience
curl -s http://localhost:9090/api/v1/targets | python3 -m json.tool | grep -E '"job"|"health"'

# Rules
curl -s http://localhost:9090/api/v1/rules | python3 -m json.tool | grep '"name"'
```

| UI | Address |
| --- | --- |
| Grafana | <http://localhost:3000> |
| Prometheus | <http://localhost:9090> |
| VictoriaMetrics | <http://localhost:8428> |

---

## 9. What this step deliberately leaves out

- **No exporters for PostgreSQL, Oracle or Redis themselves.** The services' *client-side* view of
  each is instrumented; the servers' internals need a sidecar exporter apiece, which is infrastructure
  work rather than the application metrics this step is about.
- **Kong is not scraped.** Its admin port serves no Prometheus endpoint unless the `prometheus` plugin
  is enabled, and it sits on the edge network rather than the observability one — so it needs both a
  gateway change and a network change. Better as a revision of step 06 than as a job that shows up
  permanently down here.
- **No Alertmanager**, as above.
- **No exemplars.** Linking a latency bucket to the trace that produced it needs trace ids in the
  metric, which arrives with step 12.
