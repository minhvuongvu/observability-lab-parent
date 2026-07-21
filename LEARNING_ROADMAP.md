# Learning Roadmap

The order in which this system is built, and what each step teaches. Every step is self-contained,
leaves the build green, and is documented before the next one starts.

Specifications: `PROMPT_MICROSERVICE_OBSERVABILITY_LAB.md` (what to build) and
`PROMPT_MICROSERVICE_OBSERVABILITY_STEPS.md` (the order).

---

## Status

| Step | Scope | Status |
| --- | --- | --- |
| 01 | Repository foundation: parent POM, modules, docs skeleton | **Complete** |
| 02 | Infrastructure: Docker Compose, networks, volumes, healthchecks | **Complete** |
| 03 | Shared library: DTOs, exceptions, correlation, MDC, base entities | **Complete** |
| 04 | Order Service: CRUD, validation, actuator, OpenAPI, Kafka producer, Redis | **Complete** |
| 05 | Inventory Service: CRUD, validation, actuator, Kafka consumer, Redis | **Complete** |
| 06 | API gateway: Kong routing, rate limiting, JWT plugin; Nginx | **Complete** |
| 07 | Authentication: Keycloak realm, clients, roles, users, JWT flow | **Complete** |
| 08 | Service discovery: Consul registration, health, KV configuration | **Complete** |
| 09 | Integration: end-to-end order flow, outbox, Kafka events, MinIO, retry, DLQ | **Complete** |
| 10 | Logging: JSON logs, MDC, Fluent Bit, Fluentd, Promtail, Loki, OpenSearch | **Complete** |
| 11 | Metrics: Micrometer, Prometheus, VictoriaMetrics, business metrics | **Complete** |
| 12 | Tracing: OpenTelemetry agent and Collector, Tempo, Jaeger, Zipkin | **Complete** |
| 13 | Profiling: Pyroscope agent and server, CPU/alloc/heap/lock profiles | **Complete** |
| 14 | Dashboards: production-quality Grafana dashboards per signal | **Complete** |
| **15** | **Enterprise gRPC communication** — proto contracts, streaming, deadlines, gRPC observability | **Designed** |
| 16 | Failure simulation: timeouts, leaks, CPU spikes, DLQ, circuit breakers | Planned |
| 17 | Documentation: runbook, guides, sequence diagrams, final README | Planned |

> **Numbering note.** gRPC is inserted as step 15; failure simulation and documentation shift to 16
> and 17. The reason is dependency, not preference: the gRPC chaos scenarios in
> [GRPC_FAILURE_SIMULATION.md](GRPC_FAILURE_SIMULATION.md) belong in the failure-simulation step, so
> gRPC has to exist first. Building failure simulation twice — once for HTTP, again for gRPC — would
> be worse than inserting one step.

---

## Step 15 — Enterprise gRPC Communication

### Why here

Every prerequisite is already in place, and the step is much cheaper because of it:

| Depends on | Provides |
| --- | --- |
| Step 08 — Consul | Client-side load balancing needs a resolver and healthy-instance filtering |
| Step 10 — Logging | The correlation schema gRPC logs extend, and the MDC keys the agent writes into |
| Step 11 — Metrics | Common tags, histogram policy, RED conventions |
| Step 12 — Tracing | **The OTel agent already instruments gRPC.** Traces work with no new code |
| Step 13 — Profiling | Trace→profile linking works across the protocol boundary unchanged |
| Step 14 — Dashboards | The generator, conventions and folder structure the gRPC dashboard joins |

Attempting gRPC before step 12 would mean hand-writing propagation that the agent provides free.

### Learning objectives

**Protocol and contract**

- Read and write Protocol Buffers; understand the wire format and why field numbers are permanent
- Design a proto that can evolve: additive change, reserved fields, enum zero values
- Distinguish the four RPC types and choose between them by access pattern
- Recognise wire-breaking changes, and automate their detection with `buf breaking`

**Communication design**

- Explain *why* REST at the edge, gRPC internally, and Kafka asynchronously — from coupling, not preference
- Identify when gRPC is the wrong answer (external APIs, fan-out, availability decoupling)
- Design a synchronous optimisation over an asynchronous invariant, safely

**Observability**

- Trace propagation from an HTTP header into gRPC metadata via W3C Trace Context
- Read a CLIENT/SERVER span pair and derive the network cost from the gap
- Build RED and USE dashboards for an RPC service
- Recognise the saturation signal — the gRPC executor pool — that no other metric reveals
- Log RPCs without logging payloads, tokens or metadata
- Know which failures each signal is **blind** to, and what covers the gap

**Reliability**

- Set deadlines that shrink inward, and understand propagation
- Map exceptions onto status codes correctly, and know why `INTERNAL` vs `UNAVAILABLE` changes behaviour
- Configure retries that cannot amplify an outage
- Configure a circuit breaker that trips on slowness, and never on business outcomes
- Explain why an L4 load balancer pins gRPC traffic, and what to do instead

**Troubleshooting**

- Diagnose `DEADLINE_EXCEEDED` from the client/server duration gap
- Find an executor-pool exhaustion that leaves CPU, heap and the DB pool all healthy
- Spot a load-balancing problem from the fact that scaling out changed nothing
- Detect a contract violation that produces no error in any of the four signals

### Deliverables

| Artefact | Content |
| --- | --- |
| `proto/inventory/v1/inventory_service.proto` | The contract: 5 RPCs across all four types |
| Inventory gRPC server | Port 9082, interceptors for correlation, logging, metrics, exception mapping |
| Order gRPC client | Consul-resolved channel, `round_robin`, deadlines, retry config, circuit breaker |
| Token-relay interceptor | Bearer propagation, mirroring the existing Feign relay |
| `buf lint` + `buf breaking` in CI | Mechanical contract protection |
| gRPC dashboard | RED, USE, client-vs-server, streaming, contract version, protocol comparison |
| Alert rules | Fault rate, deadline exceeded, executor saturation, channel not ready |
| Chaos endpoints | 7 scenarios, profile- and role-guarded |

### Exercises, in order

1. **Measure the problem first.** Time `POST /api/v1/orders/availability` with 1, 10 and 25 lines
   over REST — the current implementation makes one HTTP call per line, so this is a genuine N+1
   remote call. Record the trace. This baseline is what the whole step is justified against; see
   [GRPC_ENHANCEMENT_ANALYSIS.md §2.1](GRPC_ENHANCEMENT_ANALYSIS.md#21-the-concrete-problem-an-n1-remote-call-on-the-checkout-path).
2. **Write the proto.** Start with `CheckStock`; add `BatchCheckStock` once the unary case works.
3. **Compare.** Same 25-line basket over both protocols. Compare latency, payload size, span count.
4. **Follow one trace** from Kong through HTTP into gRPC into Oracle. Find the CLIENT/SERVER pair and
   compute the network cost.
5. **Break the mapping deliberately.** Return `INTERNAL` for a database timeout and watch the retry
   policy stop working. Fix it to `UNAVAILABLE` and watch it recover.
6. **Remove a deadline** and watch the trace go blind (scenario 4).
7. **Set `pick_first`** with two instances and watch scaling out achieve nothing (scenario 6).
8. **Reuse a field number** and watch all four signals report perfect health while the answers are
   wrong (scenario 5).
9. **Load-test** until something saturates. Predict which resource first; then check.

Exercises 5–8 are the valuable ones. Each is a mistake that looks correct in code review.

### Assessment

You have understood this step when you can:

- Justify each protocol from the coupling its hop tolerates, without saying "gRPC is faster"
- Explain why the reservation flow must **not** move to gRPC
- Trace one request across three protocols and account for every span
- Say which failures are invisible in traces, and why
- Explain why `NOT_FOUND` must not open a circuit breaker or count toward an error rate

---

## What each step teaches

| Step | Core concept | Signature lesson |
| --- | --- | --- |
| 01–02 | Build and infrastructure topology | Reproducible environments; healthchecks over sleeps |
| 03 | Cross-cutting platform code | Correlation must be identical everywhere, so it lives in one library |
| 04–05 | Bounded contexts, one owner per datastore | Polyglot persistence produces genuinely different failure signatures |
| 06–07 | Edge policy and identity | Verify at the edge *and* in the service; defence in depth |
| 08 | Discovery and external config | A health check nothing routes on is decorative |
| 09 | Event-driven integration | The dual-write problem, and why the outbox is the only honest fix |
| 10 | Structured logging | Label cardinality; drop logs rather than block request threads |
| 11 | Metrics | Histograms over means; pre-computed percentiles cannot be aggregated |
| 12 | Distributed tracing | Span links across async boundaries; the agent's reach |
| 13 | Continuous profiling | `itimer` over `cpu`; allocation vs live heap |
| 14 | Dashboards | Verify every query returns data; a healthy system renders zero, not "No data" |
| **15** | **gRPC** | **Protocol choice follows coupling; some failures are invisible to all four signals** |
| 16 | Failure simulation | A resilience mechanism never observed working is an assumption |
| 17 | Documentation | Explain *why*; a decision without a rationale is folklore |

---

## Suggested reading order

**Newcomer** — understand the system:

1. [README.md](README.md)
2. [SYSTEM_ARCHITECTURE.md](SYSTEM_ARCHITECTURE.md)
3. [docs/Observability.md](docs/Observability.md)
4. [docs/Architecture.md](docs/Architecture.md)

**Studying communication:**

1. [docs/Kafka.md](docs/Kafka.md) — the asynchronous half
2. [GRPC_ENHANCEMENT_ANALYSIS.md](GRPC_ENHANCEMENT_ANALYSIS.md) — why gRPC, from a real defect
3. [GRPC_ARCHITECTURE.md](GRPC_ARCHITECTURE.md) — how it fits
4. [GRPC_PROTO_DESIGN.md](GRPC_PROTO_DESIGN.md) — contract discipline
5. [GRPC_ERROR_HANDLING.md](GRPC_ERROR_HANDLING.md) — status codes and reliability

**Studying observability:**

1. [docs/Logging.md](docs/Logging.md) → [docs/Metrics.md](docs/Metrics.md) →
   [docs/Tracing.md](docs/Tracing.md) → [docs/Profiling.md](docs/Profiling.md)
2. [GRPC_OBSERVABILITY.md](GRPC_OBSERVABILITY.md) — what a second protocol adds
3. [GRPC_FAILURE_SIMULATION.md](GRPC_FAILURE_SIMULATION.md) — proving it works

**Interview preparation** — the questions this system answers concretely:

| Question | Where |
| --- | --- |
| REST vs gRPC vs messaging? | [SYSTEM_ARCHITECTURE.md §2](SYSTEM_ARCHITECTURE.md#2-communication-matrix) |
| How do you version an internal API? | [GRPC_PROTO_DESIGN.md §3](GRPC_PROTO_DESIGN.md#3-why-this-design-is-production-safe) |
| How does tracing cross an async boundary? | [docs/Tracing.md §3](docs/Tracing.md), span links |
| Dual writes between a DB and a broker? | [docs/Kafka.md §4](docs/Kafka.md), the outbox |
| Retries without causing an outage? | [GRPC_ERROR_HANDLING.md §3](GRPC_ERROR_HANDLING.md#3-retries) |
| What would you alert on? | [docs/Metrics.md §6](docs/Metrics.md), [GRPC_OBSERVABILITY.md §2.5](GRPC_OBSERVABILITY.md#25-alerts) |
| How do you know your observability is sufficient? | [GRPC_FAILURE_SIMULATION.md](GRPC_FAILURE_SIMULATION.md), the coverage matrix |
