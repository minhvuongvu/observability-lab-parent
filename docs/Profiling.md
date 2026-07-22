# Profiling

Step 13 adds the fourth signal, and the one that answers a question none of the other three can.

A metric says the service is slow. A trace says *which span* was slow. Only a profile says **which
lines of code** burned the CPU, allocated the memory, or held the lock inside that span.

The word that matters is **continuous**. Traditional profiling means reproducing a problem under a
profiler — which is exactly what cannot be done for the incident that happened at 03:00 last Tuesday.
Sampling cheaply enough to leave running all the time means the profile already exists when it is
needed.

---

## 1. How it is attached

A second `-javaagent`, alongside the OpenTelemetry one, wrapping **async-profiler**:

```
java -javaagent:tools/opentelemetry-javaagent.jar \
     -javaagent:tools/pyroscope.jar \
     -jar order-service.jar
```

Both are resolved by [`scripts/agents.sh`](../scripts/agents.sh) — through Maven, so each version is
pinned once in the parent POM, the download is checksummed by the resolver, and `tools/` stays
git-ignored. Neither is a dependency of any module; they instrument the application from outside it.

```bash
./scripts/agents.sh all                       # resolve every agent
./scripts/infra.sh up      # both agents are baked into the image at /opt/agents and
                           # attached via JAVA_TOOL_OPTIONS in docker-compose.services.yml

# To profile without them, drop the -javaagent from that variable. It is one line
# per service, and no image rebuild — which is the reason the flags live there.
```

---

## 2. The four profile types

async-profiler can only record **one event** in its collapsed format. Setting `PYROSCOPE_FORMAT=jfr`
is what makes all four available at once — otherwise choosing CPU means giving up allocation and lock
data entirely.

| Type | Pyroscope name | Configured by | Answers |
| --- | --- | --- | --- |
| **CPU** | `process_cpu` | `PYROSCOPE_PROFILER_EVENT=itimer` | Which methods burned CPU |
| **Allocation** | `memory` | `PYROSCOPE_PROFILER_ALLOC=512k` | Which methods allocated the most |
| **Live heap** | `memory` (alloc_live) | `PYROSCOPE_ALLOC_LIVE=true` | Which allocations are **still retained** |
| **Lock contention** | `mutex`, `block` | `PYROSCOPE_PROFILER_LOCK=10ms` | Which locks threads waited on |

Three of those settings are worth explaining:

- **`itimer`, not `cpu`.** `itimer` samples on a wall-clock timer, so it also attributes time spent
  blocked. `cpu` uses perf events and only counts time actually on-CPU — on a service that spends
  most of its life waiting for a database, that produces a flame graph of almost nothing.
- **Allocation and lock are *thresholds*, not switches.** Sample every 512 KB allocated, and any lock
  held longer than 10 ms. Sampling every allocation would cost more than the application does.
- **Allocation and live heap are different questions.** `alloc` says what allocated the most, and most
  of that is short-lived garbage the collector reclaims for free. `alloc_live` says what is *still
  holding memory* — that is the one that finds a leak.

### A configuration trap worth knowing

The environment variables are `PYROSCOPE_PROFILER_ALLOC` and `PYROSCOPE_PROFILER_LOCK` — **not**
`PYROSCOPE_PROFILING_*`. Getting them wrong fails silently: the agent starts, logs "Profiling
started", uploads successfully every ten seconds, and delivers CPU profiles only. The allocation and
lock views simply never appear, and nothing anywhere says why.

The way to catch it is `PYROSCOPE_LOG_LEVEL=debug`, which prints the resolved config:

```
Config{... profilingEvent=ITIMER, profilingAlloc='', profilingLock='', ...}
                                                 ^^              ^^
```

---

## 3. Correlating with traces

Pyroscope's OpenTelemetry extension is loaded **by the OTel agent** rather than as a third javaagent:

```bash
OTEL_JAVAAGENT_EXTENSIONS=tools/pyroscope-otel.jar
```

It stamps the active span id onto the profiling labels. That is what makes Grafana's
`tracesToProfiles` link work: a slow span leads to the flame graph of the CPU **that span** actually
burned, rather than to a whole-process average in which one slow request is invisible.

Without it, traces and profiles are two unrelated views of the same process.

## 4. All four signals, linked

Every link is configured in both directions, because an identifier copied between browser tabs is a
workflow people abandon during an incident.

```
        logs  ──trace_id──>  traces  ──span_id──>  profiles
          ^                    │
          └────trace_id────────┘
                               └──service──> metrics
```

| From | To | Mechanism |
| --- | --- | --- |
| Log line | Trace | Loki derived field on `trace_id` |
| Trace | Logs | `tracesToLogsV2`, filtered by trace id |
| Trace | Metrics | `tracesToMetrics`, request rate for the span's service |
| Trace | **Profile** | `tracesToProfiles`, via the span id label |

The labels are the same vocabulary throughout — `service`, `environment` — so the same words filter
all four signals. That was the point of applying common tags at the registry in step 11 and matching
the MDC keys in step 12.

---

## 5. Cardinality, again

Pyroscope stores profiles the way Loki stores logs and Tempo stores traces: a small index over labels
with the bulk compressed into blocks. **Every distinct label combination is a series**, so the same
rule applies here as everywhere else — `max_label_names_per_series` is capped in
[`pyroscope.yml`](../infrastructure/pyroscope/pyroscope.yml) so a label accidentally carrying a
request id cannot multiply the series count without limit.

Note the tension with span-level profiles: the span id label is *deliberately* high-cardinality. It is
attached by the extension for the duration of a span rather than being a stored series label, which
is what keeps it affordable.

---

## 6. Verify it

```bash
# Which services and profile types Pyroscope has
curl -s http://localhost:4040/querier.v1.QuerierService/Series \
  -H 'content-type: application/json' \
  -d '{"matchers":["{}"],"label_names":["service_name","__name__"]}'
#   order-service     -> block, memory, mutex, process_cpu
#   inventory-service -> block, memory, mutex, process_cpu

# A CPU flame graph as JSON
NOW=$(python3 -c 'import time;print(int(time.time()*1000))'); FROM=$((NOW - 600000))
curl -s "http://localhost:4040/pyroscope/render?query=process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name=\"order-service\"}&from=${FROM}&until=${NOW}&format=json"

# What the agent actually resolved
# Add PYROSCOPE_LOG_LEVEL=debug to the service's environment in
# docker/compose/docker-compose.services.yml, then:
./scripts/infra.sh up -d order-service
```

| UI | Address |
| --- | --- |
| Pyroscope | <http://localhost:4040> |
| Grafana (Explore → Pyroscope) | <http://localhost:3000> |

In Grafana, the useful path is: **Explore → Tempo → find a slow span → Profiles tab**. That is the
whole chain working — a metric flagged the latency, the trace located the span, and the profile names
the method.

---

## 7. What this step deliberately leaves out

- **No profiles for the infrastructure.** Kafka, PostgreSQL and Oracle are not profiled; async-profiler
  attaches to a JVM, and the databases here are not ones we run the JVM of.
- **No differential flame graphs in a dashboard.** Pyroscope compares two time ranges natively in its
  own UI, which is where that comparison actually belongs; a Grafana panel would be a worse version of
  it.
- **Nothing yet burns interesting CPU.** The flame graphs are dominated by framework startup and idle
  poll loops, because the business logic is deliberately trivial. Step 17's failure-simulation
  endpoints — CPU spike, memory leak, lock contention — are what give these profiles something worth
  looking at, and they were written with this step in mind.
