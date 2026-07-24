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
| 15 | Enterprise gRPC communication — proto contract, streaming, deadlines, retries, circuit breaker, gRPC observability | **Complete** |
| 16 | Alerting: rule categories, routing to email and webhook, exporters, alert guide and matrix | **Complete** |
| — | Containerisation: both services in Docker, four networks collapsed into one, k6 load generation, Toxiproxy fault injection — [Simulation.md](docs/Simulation.md) | **Complete** |
| 17 | Failure simulation: 14 chaos endpoints, a scenario runner, 13 documented scenarios — [FailureSimulation.md](docs/FailureSimulation.md) | **Complete** |
| 18 | Reference documentation: deployment, runbook, troubleshooting, performance, security, sequence and infrastructure diagrams | **Complete** |
| 19 | Learning guides: getting started, operations, configuration, debugging walkthroughs, graded exercises | **Complete** |
| 20 | Secret management: Vault (real sealed server), AppRole per service, KV v2, dynamic PostgreSQL credentials, audit log into Loki — [Vault.md](docs/Vault.md) | **Complete** |

> **Numbering note.** Two steps have been inserted since the original plan, and everything after each
> shifted rather than being dropped.
>
> - **gRPC became step 15.** The gRPC chaos scenarios in
>   [GRPC_FAILURE_SIMULATION.md](GRPC_FAILURE_SIMULATION.md) belong in the failure-simulation step, so
>   gRPC has to exist first. Building failure simulation twice — once for HTTP, again for gRPC — would
>   be worse than inserting one step.
> - **Alerting became step 16**, ahead of failure simulation. The order is a dependency, not a
>   preference: failure simulation exists to prove that a fault produces the signal somebody is
>   paged by, and that claim is untestable until the alerts and their routing exist. Doing it the
>   other way round means writing the chaos endpoints, then writing the alerts, then going back to
>   re-run every scenario.
> - **Containerisation is unnumbered**, because it is not a step in the original plan — it is the
>   accumulated repayment of every "once the services are containerised" note the earlier steps left
>   behind, in Kong's upstreams, Prometheus' targets, Consul's advertised address and the log
>   pipeline's source. It arrived when it did because step 17 needs it: you cannot put a fault proxy
>   in front of a dependency, or cap a service's CPU, when the service is a process on a laptop.
>
>   Step 17 is therefore now the *remainder* — the faults that can only be produced from inside the
>   process, plus the tooling that makes all of them repeatable: one command per failure, and a
>   written guide stating what every signal should show before the scenario is run. Everything
>   injectable from outside the process already exists in
>   [docs/Simulation.md](docs/Simulation.md).
>
> Failure simulation is now step 17 and documentation step 18.

---

## Step 15 — Enterprise gRPC Communication

> **Built.** The implementation and how to exercise it are in [docs/Grpc.md](docs/Grpc.md);
> the documents below are the design that preceded it, and remain the reasoning behind each
> decision.

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

## Step 16 — Alerting

> **Built.** The implementation, the full alert matrix and the response for each alert are in
> [docs/Alerting.md](docs/Alerting.md).

### Why here

Alerting is the first step whose output is aimed at a *person* rather than at a query. Everything
before it produced signals; this decides which of them are worth waking someone for.

| Depends on | Provides |
| --- | --- |
| Step 10 — Logging | The correlation ids an alert annotation has to carry to be actionable |
| Step 11 — Metrics | The rule engine, the recording rules, and the eleven ad-hoc alerts this step gives a taxonomy to |
| Step 13 — Profiling | The heap and CPU series the JVM alerts fire on |
| Step 14 — Dashboards | Every alert must link to the panel that explains it, or it is a dead end |
| Step 15 — gRPC | The internal hop's saturation signals, which have no HTTP equivalent |

**And why before failure simulation.** Step 17 exists to prove that a real fault produces the signal
somebody is paged by. That claim is untestable while the alerts and their routing do not exist —
running it the other way round means writing the chaos endpoints, then the alerts, then re-running
every scenario to check them.

### What this step has to add first

The current rules only alert on what the *services themselves* export. Four of the required alerts
have no series to fire on yet, and this is the honest cost of the step:

| Required alert | Needs |
| --- | --- |
| High CPU, High Memory, Disk Usage | A node exporter — none of these are JVM metrics |
| PostgreSQL Down, Oracle Down, Slow Queries | Database exporters. The services' *client-side* view (Hikari) is instrumented; the servers' internals are not |
| Redis Down, Redis High Latency | A Redis exporter. Lettuce command latency covers the client half only |
| Kafka Broker Down, Consumer Lag | A Kafka exporter, or JMX scraping on the broker |

[docs/Metrics.md §9](docs/Metrics.md) records the absence of these as a deliberate omission of step
11. Step 16 is where it stops being deliberate.

**Alert targets require a router.** Email and webhook delivery is Alertmanager's job (or Grafana
unified alerting's) — neither is in the stack today, and both `docs/Metrics.md` and
the alert rules currently say so in as many words. Adding one is part of this step, and both of
those statements become wrong the moment it lands.

### Learning objectives

**What to alert on**

- Alert on **symptoms users feel**, not on causes: "checkout is failing" over "CPU is at 90%"
- Separate *pages* from *tickets* from *context*, and match the category to the response
- Write a threshold that means the same thing at 10 rps and at 10 000 rps — ratios, not counts
- Recognise the alert that fires on data quality rather than service health, and refuse to ship it

**Routing and delivery**

- Route by category and by owning service, so a page reaches whoever can act on it
- Group, inhibit and silence: one broker failure must not produce forty notifications
- Understand why an alert with no runbook link is an alert that will be escalated blindly

**Rule mechanics**

- `for:` durations, and why a flapping alert is worse than a missing one
- Prometheus rules versus Grafana rules — what each can express, and why running both here is a
  deliberate comparison rather than duplication
- Alert on the *absence* of a signal (`absent()`, `up == 0`); a dead process reports nothing at all,
  and a health-based rule simply goes quiet

**Operations**

- Write an alert annotation that says what broke, what it affects, and where to look next
- Build an alert matrix: signal → threshold → category → owner → first action
- Measure the alerts themselves: firing rate, time-to-acknowledge, and how many were actionable

### Deliverables

| Artefact | Content |
| --- | --- |
| Exporters | Node, PostgreSQL, Oracle, Redis and Kafka — the series the required alerts need |
| Prometheus rules | Grouped by category (critical / warning / information) and by subsystem |
| Alertmanager | Routing tree, receivers for email and webhook, grouping and inhibition rules |
| Grafana alert rules | Contact points, notification policies, and the rules expressed Grafana-side |
| Alert panels | Firing state and history on the dashboards, per subsystem |
| `docs/Alerting.md` | The alert guide: philosophy, categories, routing, and how to add or silence one |
| Alert matrix | Every alert: expression, threshold, category, what it means, first action |
| Incident response | Per alert, the suggested first three steps and the dashboard that explains it |

### Exercises, in order

1. **Count the current alerts and classify them.** Which are symptoms and which are causes? Which
   would you actually be woken by at 03:00?
2. **Break one thing and count the notifications.** Stop Kafka and see how many alerts fire from one
   fault. That number is the argument for grouping and inhibition.
3. **Write a bad alert deliberately** — an absolute error count rather than a ratio — then drive
   traffic up and watch it become meaningless.
4. **Fire an alert with no `for:`** and watch it flap. Add the duration and watch it settle.
5. **Kill a service** and confirm `up == 0` fires while every health-based rule stays silent.
6. **Route a critical and a warning to different receivers**, and verify the webhook payload contains
   enough to act on without opening Grafana.
7. **Write the runbook link into the annotation**, then hand the alert to somebody who has not read
   this repository and see whether they can act on it.

Exercises 2 and 3 are the valuable ones: both produce an alert channel that is technically correct
and operationally useless.

### Assessment

You have understood this step when you can:

- Justify every alert from the user-visible symptom it represents
- Explain why `NOT_FOUND`, a rejected order and an insufficient-stock refusal must never page anyone
- Say which alerts fire on the *absence* of data, and why they cannot be health-based
- Describe what happens to forty simultaneous alerts caused by one root failure
- Point at an alert in the matrix and state its first response action from memory

---

## Step 18 — Reference Documentation

### Why here

Everything is built. Nothing that follows adds a capability, so this is the last point at which the
documentation can describe a finished system rather than a moving one.

It is also a **dependency of step 19**. A debugging walkthrough points at the metrics and tracing
references; a getting-started guide points at the deployment guide for what a prerequisite is actually
for. Writing the task layer first means writing against documents that do not exist.

### The organising principle

Step 18 organises by **subsystem** and is read by lookup:

> "What is Loki configured to do?"

Step 19 organises by **task** and is read in order, at a keyboard:

> "An order is stuck PENDING. Where do I look?"

Different organising principle, different reading mode. A reader who wants one is badly served by the
other, and a document that tries to be both is worse than either.

### Deliverables

| Artefact | Content |
| --- | --- |
| `docs/Deployment.md` | What a deployment is, prerequisites, the configuration surface and what must change together, startup ordering, verification, redeploy and rollback, and what this deployment is deliberately not |
| `docs/Runbook.md` | Procedures: the five pre-checks, service lifecycle, per-component operations, six incident procedures, data procedures, experiment protocol, change procedures, and how to prove the system healthy again |
| `docs/Troubleshooting.md` | Symptom → cause → check → fix, across startup, auth, addressing, data, messaging, the four telemetry pipelines, the simulation stack and the build — plus the signals that mislead |
| `docs/Performance.md` | The measured ceiling, the latency budget chain, what runs out first, the measurement protocol, the tuning playbook, the cost of observing, and what not to conclude |
| `docs/Security.md` | The layered controls, identity and the issuer trap, double token verification, the authorization matrix, the header trust model, credentials and least privilege, container hardening, and what is deliberately not secured |
| `docs/SequenceDiagrams.md` | Fifteen worked flows, each with what to notice: transaction phases in the order lifecycle, the express reservation race, retry and dead letter, startup coupling, one request across four signals, fault injection, circuit breaker states, alert delivery, log shipping |
| `docs/InfrastructureDiagram.md` | The static map: the whole system on one network, compose-file ownership, published ports versus in-network addresses, the fault-injection topology, the startup graph, telemetry pipelines, volumes, the resource budget, and what the single network cost |
| Final `README.md` | Restructured documentation index: start-here table, operations, diagrams, reference by subsystem |

### What writing it found

Documentation that is written against the files rather than from memory finds things. This step found
three:

1. **`retry-topic` is created and never used.** `kafka-init` declares it; no code and no configuration
   references it. Retries are in-process (`FixedBackOff`, 3 attempts, 1 s) and go straight to the
   dead-letter topic. Recorded rather than quietly fixed, because an empty topic that looks like a
   broken pipeline is exactly the kind of thing a reader needs told.
2. **`scripts/load.sh`'s header disagreed with its own scenarios** — it advertised 50/800/500/20 where
   the k6 files default to 10/100/80/5. Fixed, because the true numbers are *measured* and the stale
   ones would have sent a reader chasing a system that does not exist.
3. **The sum of the memory limits is 16.1 GB**, against a documented 10 GB requirement. Both are correct
   — limits are ceilings, not reservations — but the gap is worth stating rather than leaving a reader
   to discover it during a stress run.

### Exercises, in order

1. **Take one claim from each new document and verify it against the file it describes.** Any that
   fails is a bug in the document, not in the reader.
2. **Follow the deployment verification list on a cold stack** and note which step fails first when you
   deliberately skip `chaos.sh reset`.
3. **Pick a symptom you have actually hit** and check whether the troubleshooting guide reaches it in
   under three lookups. If not, that is the gap to write.
4. **Trace the create-order sequence diagram against the code**, and find where the transaction phase
   matters. Change `BEFORE_COMMIT` to `AFTER_COMMIT` on the outbox listener and reason about what
   breaks before running it.
5. **Read the security guide's §12 and count how many gaps you would have assumed were covered.**

### Assessment

You have understood this step when you can:

- Say which document answers a given question without opening any of them
- Explain why the published-port versus in-network-address distinction is the one rule that resolves
  most confusion in this stack
- State the latency budget chain from Nginx to the Oracle query, and the rule that orders it
- Name the binding constraint on throughput, and prove it with one metric
- List three things this deployment is not, and what each would cost in production
- Explain why a document organised by subsystem cannot also serve a reader at a keyboard

---

## Step 19 — Learning and Practice Guides

### Why here, and why last

This lab exists to be **learned from**, not merely to run. Steps 1–18 document the system; this step
documents how to *work with* it — bring it up, operate it, change it safely, break it deliberately,
and find the cause using the tools.

It is last because step 18 is a **dependency**. A debugging walkthrough points at the metrics and
tracing references; a getting-started guide points at the deployment guide for what a prerequisite is
actually for. Writing the task layer first means writing against documents that do not exist.

### The organising principle, restated

| | Step 18 | Step 19 |
| --- | --- | --- |
| Organised by | Subsystem | Task |
| Read by | Lookup | In order, at a keyboard |
| Answers | "What is Loki configured to do?" | "An order is stuck PENDING. Where do I look?" |

### Deliverables

| Artefact | Content |
| --- | --- |
| `GETTING_STARTED.md` | Empty clone → working stack → a confirmed order → where to watch it in Grafana. What each prerequisite is *for*, what "it worked" looks like at every stage, and the three things most likely to go wrong on a first run |
| `docs/Operations.md` | Lifecycle, restarting and rebuilding one service, reading health correctly, logs per component, scaling and what breaks, disk and volumes, and the operational gotchas |
| `docs/Configuration.md` | Every knob and what moves when it does; published port versus in-network address; the load-bearing values; retention/TTL/sampling with **measured** values; what must change together |
| `docs/Debugging.md` | The centrepiece: symptom → signal → tool → query, four investigations from real measurements each naming the wrong turn, how to pivot between signals, what each tool is bad at |
| `docs/Exercises.md` | Six graded levels, each a question with a checkable answer; solutions in a separate section |

### The rule that made this step worth doing

> **Every command must be copy-pasteable and verified against the running stack.**

That is not a style preference — it is what turned this step into a bug hunt. Verifying the guides
found, in the repository and in the step-18 documents written days earlier:

| Found | Where |
| --- | --- |
| `chaos.sh reset` **completely broken on Windows** — Python's `print()` emits `\r\n`, so proxy names carried a trailing `\r` and every URL was rejected | `scripts/chaos.sh` |
| `chaos.sh` picked the Windows Store `python3` stub, which passes `command -v` and then refuses to run | `scripts/chaos.sh` |
| Topics have **3 partitions, not 7** — the second argument to `create_topic` is *retention days*. This inverts the conclusion: `concurrency: 3` already matches 1:1 | 4 step-18 documents |
| Prometheus retention is **1 day**, not the documented 15; VictoriaMetrics 30 days, not 90 | `SystemDesign.md §11` |
| A stale `.env` has **two** failure modes, and the harder one starts nothing at all | `Deployment.md`, `Troubleshooting.md` |
| Kafka 4.x moved `GetOffsetShell`; the old name fails silently as "0 messages" when stderr is discarded | `Runbook.md` |
| One order produces **8** log lines, not 2–3; a trace has 47 spans split **28/19**, so span count and time are different questions | `Exercises.md` |

An untested command in a learning document teaches the reader that the document is unreliable, which
is the one lesson that cannot be unlearned.

### Exercises, in order

The document *is* the exercise set — six levels, from a container census to "find a claim in this
repository that the running stack contradicts". Level 6.3 has a known answer, and it is the retention
gap above.

### Assessment

You have understood this step when you can:

- Bring the stack up from an empty clone without reading anything else, and tell success from a silent
  failure at each stage
- Explain why `hikaricp_connections_pending` moves before latency does
- Take an order number and reach its trace, its logs and its flame graph without opening a UI
- Say what `pg_up == 1` means while the service reports timeouts, and why that pair *is* the diagnosis
- Predict the sustainable arrival rate from a pool size and a service time, then measure and be right
- Name the wrong turn in an investigation you just completed

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
| 16 | Alerting | Alert on symptoms a person would act on; an ignored channel is worse than none |
| 17 | Failure simulation | A resilience mechanism never observed working is an assumption |
| 18 | Documentation | Explain *why*; a decision without a rationale is folklore. Say what is **not** true, or a lab simplification gets copied into production |
| **19** | **Learning by doing** | **A command that was never run is a guess. Verifying the guide against the stack is what finds the bugs in both** |
| **20** | **Secret management** | **A failure whose symptom is delayed by an hour is harder than one that is loud. Vault sealed changes nothing visible — which is why seal state is alerted directly, not inferred** |

---

## Suggested reading order

**First time here** — get it running, then understand it:

1. [GETTING_STARTED.md](GETTING_STARTED.md) — empty clone to a confirmed order
2. [docs/Operations.md](docs/Operations.md) — running it day to day
3. [docs/Debugging.md](docs/Debugging.md) — the method, worked four times
4. [docs/Exercises.md](docs/Exercises.md) — check whether it stuck

**Newcomer** — understand the system:

1. [README.md](README.md)
2. [SYSTEM_ARCHITECTURE.md](SYSTEM_ARCHITECTURE.md)
3. [docs/InfrastructureDiagram.md](docs/InfrastructureDiagram.md) — what runs where
4. [docs/SequenceDiagrams.md](docs/SequenceDiagrams.md) — what moves through it
5. [docs/Observability.md](docs/Observability.md)
6. [docs/Architecture.md](docs/Architecture.md)

**Operating it** — the reference layer, in the order you will need it:

1. [docs/Deployment.md](docs/Deployment.md) — bring it up, and know what it is not
2. [docs/InfrastructureDiagram.md](docs/InfrastructureDiagram.md) — the map
3. [docs/Runbook.md](docs/Runbook.md) — procedures
4. [docs/Troubleshooting.md](docs/Troubleshooting.md) — when it is not yet clear what is wrong
5. [docs/Performance.md](docs/Performance.md) — measuring honestly
6. [docs/Security.md](docs/Security.md) — what protects what, and what does not

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
| What is your capacity, and what binds it? | [docs/Performance.md §2 and §5](docs/Performance.md#5-what-runs-out-first) |
| How do you set timeouts across a call chain? | [docs/Performance.md §3](docs/Performance.md#3-the-latency-budget) — a callee's budget must be smaller than its caller's |
| Where do you enforce authorization, edge or service? | [docs/Security.md §4–5](docs/Security.md#4-token-verification-twice) — both, independently |
| What would you fix first before exposing this? | [docs/Security.md §12](docs/Security.md#12-what-is-deliberately-not-secured) |
| How do you manage secrets, and where does the chain of trust end? | [docs/Vault.md §4 and §11](docs/Vault.md#4-the-bootstrap-problem) — the honest answer is a file on disk, made smaller, scoped and audited |
| Static or dynamic database credentials? | [docs/Vault.md §7](docs/Vault.md#7-dynamic-postgresql-credentials) — dynamic for the runtime pool, static for migrations, and why that split is not optional |
| How do you deploy and roll back? | [docs/Deployment.md §11](docs/Deployment.md#11-redeploy-rollback-and-teardown) — including why a migration cannot be |
