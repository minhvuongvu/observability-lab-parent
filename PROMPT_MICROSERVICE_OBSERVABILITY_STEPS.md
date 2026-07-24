# Enterprise Microservice Observability Lab

## Execution Guide

This document defines the execution plan.

The complete project specification is described in:

> PROMPT_MICROSERVICE_OBSERVABILITY_LAB.md

That document is the SINGLE SOURCE OF TRUTH.

Never contradict it.

---

# Global Rules

For every execution:

- Read PROMPT_MICROSERVICE_OBSERVABILITY_LAB.md first.
- Read the current repository.
- Understand the current project state.
- Implement ONLY the requested step.
- Do NOT implement future steps.
- Keep existing code unchanged unless required.
- Never rewrite working code unnecessarily.
- Keep architecture consistent.
- Keep naming conventions consistent.
- Build after every change.
- Fix compilation errors before stopping.
- Update documentation related to this step.
- Stop immediately after this step is completed.

---

# Definition of Done

Every step must satisfy ALL conditions.

- Project builds successfully.
- No compilation errors.
- Docker configuration is valid.
- Documentation updated.
- No TODO placeholders.
- No incomplete implementation.
- Ready for Git commit.

---

# STEP 01

## Repository Foundation

### Goal

Create the project skeleton.

### Deliverables

- Parent Maven project
- Java 21
- Spring Boot 3
- Multi-module structure
- order-service
- inventory-service
- shared-library
- infrastructure
- docker
- docs
- scripts

Generate

- README
- Architecture.md
- SystemDesign.md

Generate Mermaid architecture diagram.

Do NOT implement business logic.

Do NOT implement Docker.

Do NOT implement infrastructure.

Stop after completion.

---

# STEP 02

## Infrastructure

### Goal

Create infrastructure only.

Generate Docker Compose.

Include

- PostgreSQL
- Oracle XE
- Kafka
- Kafka UI
- Redis
- MinIO
- Consul
- Keycloak
- Kong Gateway
- Nginx

Create

- Networks
- Volumes
- Health Checks
- Init Scripts
- Restart Policies

Do NOT modify application code.

Stop after infrastructure is complete.

---

# STEP 03

## Shared Library

Create shared-library.

Include

- Common DTO
- API Response
- Base Exception
- Global Exception Handler
- Common Utilities
- Logging Utilities
- Correlation ID
- MDC Utilities
- Trace Utilities
- Base Entity
- Audit Entity
- Validation Utilities

No business logic.

Stop.

---

# STEP 04

## Order Service

Implement ONLY Order Service.

Database

PostgreSQL.

Include

- CRUD
- Validation
- Actuator
- Health Endpoint
- OpenAPI
- Feign Client Configuration
- Kafka Producer
- Redis Cache
- Unit Test

Stop.

---

# STEP 05

## Inventory Service

Implement ONLY Inventory Service.

Database

Oracle.

Include

- CRUD
- Validation
- Actuator
- Health Endpoint
- Kafka Consumer
- Redis
- Unit Test

Stop.

---

# STEP 06

## API Gateway

Configure

- Kong Gateway
- Nginx

Routing

Rate Limit

JWT Plugin

Health Check

No observability yet.

Stop.

---

# STEP 07

## Authentication

Configure Keycloak.

Create

Realm

Clients

Roles

Users

JWT Flow

Protect APIs.

Stop.

---

# STEP 08

## Service Discovery

Configure Consul.

Implement

Service Registration

Health Check

Configuration Management

Consul KV

Spring Cloud Consul

Stop.

---

# STEP 09

## Integration

Integrate

Kafka

Redis

MinIO

Implement

Order Flow

Kafka Events

Object Upload

Cache

Retry

DLQ

Stop.

---

# STEP 10

## Logging

Integrate

SLF4J

Logback

JSON Logging

MDC

Correlation ID

Request ID

Trace ID

Span ID

Create configuration for

Fluent Bit

Fluentd

Promtail

Loki

OpenSearch

Elasticsearch

Grafana Datasource

Generate sample dashboards.

Stop.

---

# STEP 11

## Metrics

Integrate

Micrometer

Spring Boot Actuator

Prometheus

VictoriaMetrics

Grafana

Generate

HTTP Metrics

JVM Metrics

Kafka Metrics

Redis Metrics

Datasource Metrics

Business Metrics

Stop.

---

# STEP 12

## Tracing

Integrate

OpenTelemetry SDK

OpenTelemetry Collector

Tempo

Jaeger

Zipkin

Grafana

Trace

HTTP

Feign

Kafka

Redis

PostgreSQL

Oracle

MinIO

Generate trace documentation.

Stop.

---

# STEP 13

## Profiling

Integrate

Pyroscope Agent

Pyroscope Server

Grafana

Generate

CPU Profile

Heap Profile

Allocation Profile

Lock Contention Profile

Stop.

---

# STEP 14

## Observability Dashboards

Create production-quality Grafana dashboards.

Include

Logs

Metrics

Traces

Profiles

Business Metrics

Kafka

Redis

Databases

JVM

HTTP

Stop.

---

# STEP 15

## Enterprise gRPC Communication

Add gRPC service-to-service communication.

Keep REST.

Keep Kafka.

Implement

Proto Contract

Protocol Buffers Versioning

gRPC Server

gRPC Client

Unary RPC

Server Streaming

Client Streaming

Metadata Propagation

Deadline Propagation

Status Code Mapping

Retry

Circuit Breaker

Client-side Load Balancing

Service Discovery Integration

Observability

gRPC Logging

gRPC Metrics

RED Metrics

USE Metrics

Trace Propagation

Design documentation exists in

GRPC_ENHANCEMENT_ANALYSIS.md

GRPC_ARCHITECTURE.md

GRPC_PROTO_DESIGN.md

GRPC_OBSERVABILITY.md

GRPC_ERROR_HANDLING.md

GRPC_FAILURE_SIMULATION.md

Stop.

---

# STEP 16

## Alerting

### Goal

Configure production-style alerting.

Generate

Prometheus Alert Rules

Grafana Alert Rules

Alert Categories

- Critical
- Warning
- Information

Alert Targets

- Email
- Webhook

Generate alerts for

Infrastructure

- High CPU
- High Memory
- Disk Usage
- Service Down

Application

- High Error Rate
- High Latency
- Thread Exhaustion
- Heap Usage

Kafka

- Consumer Lag
- Broker Down

Redis

- High Latency
- Redis Down

Databases

- PostgreSQL Down
- Oracle Down
- Slow Queries

Generate Grafana alert panels.

---

## Documentation

Generate

- Alert Guide
- Alert Matrix
- Incident Response Suggestions

Stop.

---

# STEP 17

## Production Failure Simulation

### Already delivered

The containerisation change shipped everything that can be injected from
OUTSIDE a process. Do NOT rebuild any of it:

- Toxiproxy in front of every dependency hop, including service-to-service
- Latency, connection refusal, black hole, connection reset, bandwidth cap
- k6 load generation from inside the network: smoke, load, stress, spike, soak
- scripts/chaos.sh and scripts/load.sh
- docs/Simulation.md with seven scenarios, written signal by signal

This step covers ONLY what cannot be produced from outside the process.

A memory leak, a CPU spike, a deliberate deadlock and a forced exception all
need code. A slow database does not.

### Endpoints

Create endpoints for

Slow Request

Database Timeout

Kafka Failure

Redis Failure

Memory Leak Simulation

CPU Spike

Retry

Circuit Breaker

Dead Letter Queue

Bulk Logging

High Traffic

Deadlock

Large Payload

Exception Simulation

These endpoints exist ONLY for observability learning.

Guard every one of them.

- local and dev profiles only
- ADMIN role required
- Disabled under prod

A chaos endpoint reachable in production is not a learning tool.

It is a vulnerability.

### Scripts

Every failure must be reproducible by ONE command.

Not by a curl the reader has to assemble from the documentation.

Extend scripts/chaos.sh to drive the in-application faults alongside the
network ones it already drives.

Add a scenario runner that performs a full experiment end to end.

- Inject the fault
- Hold it for a stated duration
- Heal it
- Print what to look at, and where

Reset must always return the system to a clean state.

Print the count of active faults before every run. A load test against a
stack that still carries a toxic from an earlier experiment produces numbers
that look like a regression and are an artefact.

### Guide

Document how to simulate every production failure.

Use the form docs/Simulation.md already establishes.

- Inject - the exact command
- Expected - what EVERY signal should show, per signal
- Verify - the concrete PromQL, LogQL or trace query
- What it teaches - the failure this makes legible

State the expectation BEFORE running the scenario.

A scenario whose expected signals were written after seeing the output
confirms nothing.

Cover every endpoint above.

Cover the combinations that only appear under load. A fault injected into an
idle system tells you what the mechanism does. A fault injected into a
saturated one tells you what it does when it matters, and the two are not
the same.

Name the alert each scenario should fire, and confirm it fired. An alert that
has never fired is untested.

Where observed behaviour differs from the expectation, record BOTH. The gap
is the finding, and it is worth more than the scenario that went to plan.

Stop.

---

# STEP 18

## Reference Documentation

### Goal

Complete the REFERENCE layer: one document per subsystem, organised so a reader
who already knows what they are looking for can find it.

This is the "what is this and how is it configured" layer. The "how do I do
this" layer is step 19, and the two are not the same document written twice.

### Already delivered

Do NOT rewrite these. They exist and are current:

- docs/Architecture.md, docs/SystemDesign.md, docs/Infrastructure.md
- docs/Observability.md, docs/Logging.md, docs/Metrics.md
- docs/Tracing.md, docs/Profiling.md, docs/Alerting.md
- docs/Kafka.md, docs/Redis.md, docs/MinIO.md
- docs/Consul.md, docs/Keycloak.md, docs/Grpc.md
- docs/Simulation.md, docs/FailureSimulation.md

### Still owed

Deployment Guide

Runbook

Troubleshooting Guide

Performance Guide

Security Guide

Sequence Diagrams

Infrastructure Diagram

Final README

Stop.

---

# STEP 19

## Learning and Practice Guides

### Goal

This lab exists to be LEARNED FROM, not merely to run.

Everything up to step 18 documents the system. This step documents how to work
with it: bring it up, operate it, change it safely, break it deliberately, and
find the cause using the tools.

### Why this is not step 18

Step 18 organises by SUBSYSTEM and is read by lookup.

  "What is Loki configured to do?"

Step 19 organises by TASK and is read in order, at a keyboard.

  "An order is stuck PENDING. Where do I look?"

Different organising principle, different reading mode. A reader who wants one
is badly served by the other. Step 18 is also a DEPENDENCY: a debugging
walkthrough points at the metrics and tracing references, so writing it first
means writing against documents that do not exist.

### Do NOT duplicate

Two of the topics below are already documented in depth:

- docs/Simulation.md - network faults, k6 load, measured saturation
- docs/FailureSimulation.md - 13 in-process failure scenarios

Step 19 ROUTES to them and adds exercises. It does not restate them. A second
copy of a scenario is a second copy that goes stale, and the copy a reader finds
first will be the wrong one.

### Deliverables

GETTING_STARTED.md

- From an empty clone to a working stack, in order
- What each prerequisite is actually for
- What "it worked" looks like at every stage, so a learner can tell success
  from a silent failure
- The first order, end to end, and where to watch it in Grafana
- The three things most likely to go wrong on a first run, and how each looks

docs/Operations.md

- Start, stop, rebuild, restart one service, destroy and start clean
- Reading health: what healthy means for each component, and what it does not
- Where the logs are, per component, and how to follow one
- Scaling a service, and what breaks when you do
- Disk, volumes and cleanup
- Known operational gotchas, including the Kong balancer state after a chaos run

docs/Configuration.md

- Every knob that matters, where it lives, and what changes when it moves
- The distinction between a PUBLISHED port and an IN-NETWORK address, which is
  the single most common source of confusion in this stack
- Which values are load-bearing: resource limits set the saturation point, and
  changing them invalidates the calibrated k6 defaults
- Retention, TTLs and sampling, and what each costs
- What must change TOGETHER or the system misbehaves silently

docs/Debugging.md

The centrepiece.

- Symptom to signal to tool to query
- At least four worked investigations, start to finish, each beginning from a
  symptom a user would report rather than from a metric
- Each one must name the WRONG turn as well as the right one: the signal that
  looks relevant and is not is most of what makes debugging hard
- How to pivot between signals: metric to trace, trace to log, log to profile
- What each tool is bad at, so a reader stops looking in the wrong place

docs/Exercises.md

- Graded, hands-on, using the simulation tooling that already exists
- Each exercise poses a QUESTION to answer, not steps to follow
- The answer is a number, a query or a diagnosis - something that can be checked
- Solutions in a separate section, so the reader can try first
- Ordered by dependency: the exercise that teaches trace-to-log correlation
  comes after the one that teaches reading a trace

### Rules

Every command must be copy-pasteable and VERIFIED against the running stack.

An untested command in a learning document teaches the reader that the document
is unreliable, which is the one lesson that cannot be unlearned.

State what the reader should SEE, not only what to type. A learner cannot tell a
successful command from a silently failed one.

Prefer a worked example over an explanation. Where both are needed, the example
comes first.

Say what is NOT true. This lab simplifies in several places - single-node
everything, plaintext between services, credentials in a git-ignored file - and
a learner who mistakes a lab simplification for a production pattern has learned
something worse than nothing.

Cross-link every guide to the reference document behind it, and back.

Stop.

---

# STEP 20

## Secret Management with HashiCorp Vault

### Goal

Take the credentials the two Spring Boot services use out of a plain file and
put them behind an authenticated, audited, revocable secret store.

docs/Security.md already names this as the gap. Section 9 documents every
credential as living in `docker/compose/.env`, and the closing recommendations
say to move them to a secret manager. This step is that move, and it must
correct those documents rather than leave them describing the old arrangement.

### The distinction that governs this step

Consul KV already externalises configuration. Vault is not a second Consul.

  Configuration is a value that could appear in a screenshot.
  A secret is a value that could not.

A port number, a pool size, a retention window, a sampling rate - Consul KV.
A password, a token, an access key - Vault.

Nothing may live in both. A value duplicated across two stores has two sources
of truth and will eventually disagree, and the copy that wins will be whichever
one the reader did not check.

### Scope, stated honestly before anything is built

Vault can only serve a secret to something that can ask for it.

The two Spring Boot services can ask. They get Spring Cloud Vault.

Keycloak, Grafana, Redis, MinIO, PostgreSQL and Oracle cannot. They read
environment variables that the container runtime hands them at boot, and no
amount of Vault configuration changes that. Their bootstrap credentials stay in
`.env` for this step.

Do NOT pretend otherwise. Do NOT write a document implying the stack has no
credentials on disk when six components still do.

Closing that remaining gap needs Vault Agent rendering an env file per
container, which is a different mechanism with its own failure modes. It is a
candidate for a later step. This step does not attempt it.

### Vault itself

Persistent storage. File or integrated Raft, your call, but justify it in the
documentation.

Real initialisation. Real unseal. Real seal state.

Do NOT use `-dev` mode. Dev mode auto-unseals, keeps everything in memory and
hands out a fixed root token, which removes precisely the three things that are
hard about operating Vault and worth learning here.

Accept the cost this creates: the stack no longer comes up with one command.
Vault boots sealed after every restart and something must unseal it. Solve that
explicitly and document it - an unseal step the reader discovers by having the
stack fail is a bad first experience.

### The bootstrap problem

Vault holds the credentials. Something must hold the credentials to Vault.

The unseal keys and each service's AppRole `role-id` and `secret-id` have to
exist somewhere before Vault can serve anything. In this lab that is a
git-ignored file, which means the chain of trust still terminates in a file on
disk.

State this plainly in docs/Vault.md and in docs/Security.md. It is the honest
answer, and a reader who thinks Vault eliminated the problem rather than moving
and shrinking it has learned the wrong lesson. What actually improved: one file
instead of twenty credentials, revocable, audited, and scoped per service.

### Auth

AppRole, one per service.

A policy per service, scoped to the paths that service actually reads. Verify
the scoping by proving the negative: order-service's token must be denied when
it reads inventory-service's path, and that denial must appear in the audit log.

A policy nobody has tested a denial against is a policy that might be `*`.

### Secrets engines

KV v2, for static credentials.

Use the versioning. It is the reason to run v2 rather than v1, and a documented
rollback of a bad secret write is worth more than a paragraph explaining that
versioning exists.

Database engine, for dynamic PostgreSQL credentials.

This is the part that makes Vault something other than an encrypted `.env`.
Vault creates a real PostgreSQL user with a TTL, hands it to order-service,
renews the lease while the service runs, and drops the user when the lease ends.

Two traps, both of which must be handled rather than discovered:

Flyway needs DDL rights and creates objects that acquire an owner. A short-lived
dynamic user would own the schema and then be dropped. Migrations therefore keep
a static owner credential from KV v2. Only the runtime Hikari pool uses dynamic
credentials. This split is what real deployments do and the reasoning belongs in
the documentation.

HikariCP holds connections that authenticated once. When credentials rotate,
open connections keep working and new ones fail, so the failure surfaces minutes
later under pool growth rather than at rotation. Align `max-lifetime` with the
lease TTL so the pool recycles inside the credential's lifetime, and say what
the two numbers must satisfy relative to each other.

Oracle is not one of the database plugins built into the Vault binary. It is
distributed separately as `vault-plugin-database-oracle`, needs the Oracle
Instant Client libraries present, and has to be placed in a plugin directory and
registered in the plugin catalogue before it can be used.

Decide whether to take that on, and record the decision either way. If you do
not, inventory-service uses static KV v2 credentials and the asymmetry is a
stated limitation. Do NOT silently give one service dynamic credentials and
leave the reader to work out why the other did not get them.

### Spring integration

`spring-cloud-starter-vault-config`, joining the existing
`spring.config.import` chain alongside Consul.

Order matters and must be documented: Vault has to resolve before the datasource
is built.

Keep the `${VAR:default}` fallbacks working for local runs outside Docker. A
developer running one service from an IDE without Vault must still get a
startup, and `optional:` is how the existing Consul import already achieves it.

### Observability

Vault is infrastructure in this lab, which means it gets the same treatment as
every other component. A secret store that fails silently is worse than a file.

Metrics. Vault exposes Prometheus-format telemetry at `/v1/sys/metrics`. Add
the scrape job. Handle the authentication the endpoint requires.

Audit device. Enable one, file-backed, and route it into the existing log
pipeline so audit entries land in Loki alongside everything else. Note in the
documentation that Vault refuses to serve requests when every audit device is
failing, which is deliberate and surprising the first time it happens.

Grafana dashboard. Seal state, request rate, lease count, token count, audit
log volume.

Alerts, added to the existing Alertmanager configuration:

- Vault sealed
- Vault unreachable from a service
- Lease renewal failing
- Audit device failing

Trace the secret fetch. It happens at bootstrap, before the tracing exporter is
fully up, so say honestly what is and is not visible in Tempo rather than
claiming coverage that is not there.

### Failure simulation

Follow the form docs/Simulation.md and docs/FailureSimulation.md already
establish: Inject, Expected, Verify, What it teaches. State the expectation
before running it.

- Vault sealed while services are running and healthy
- Vault unreachable, injected with the Toxiproxy already in the stack
- Lease expired without renewal, then a new connection requested
- AppRole secret-id revoked while the service holds a valid token
- Vault restarted, therefore sealed, then a service restarted against it

The first one is the most instructive: a sealed Vault does not take down a
running service immediately. It takes it down at the next credential renewal,
which may be an hour later and will look unrelated. Make that delay visible.

### Scripts

Follow the existing `scripts/*.sh` conventions.

`scripts/vault.sh` covering init, unseal, status, seed, policy application,
dynamic credential inspection, and revocation.

Initialisation must be idempotent. Re-running it against an initialised Vault
reports state and exits cleanly - it does not error, and it does not re-init.

Extend `scripts/infra.sh` so the unseal step is part of bringing the stack up.

Extend `scripts/chaos.sh` with the Vault faults above.

### Documentation

New:

docs/Vault.md - the reference document, in the form of docs/Consul.md and
docs/Keycloak.md.

Update, because each of these currently describes an arrangement this step
replaces:

- docs/Security.md - section 9 credentials, the lab-simplification table, and
  the closing recommendations. Its "credentials in a plain .env" row is now
  partly wrong and must say exactly which parts remain true
- docs/Configuration.md - what moved to Vault, what stayed in `.env`, what must
  change together
- docs/Infrastructure.md and docs/InfrastructureDiagram.md
- docs/Architecture.md and SYSTEM_ARCHITECTURE.md
- docs/Operations.md - the unseal step, and what a sealed Vault looks like
- docs/Runbook.md and docs/Troubleshooting.md - sealed Vault, expired lease,
  denied policy
- docs/Alerting.md - the new alerts
- docs/Deployment.md, docs/SequenceDiagrams.md, docs/Observability.md
- GETTING_STARTED.md - Vault init and unseal in the first-run path
- README.md, LEARNING_ROADMAP.md, docs/Exercises.md

### Rules

Every command copy-pasteable and verified against the running stack.

`.env.example` must contain no real credential, before and after.

Nothing regresses. The stack still comes up, the order flow still runs, every
existing dashboard still populates. A secret store that breaks the system it
protects has not improved anything.

Prove a secret was read from Vault rather than from a leftover environment
variable. A service that silently fell back to `.env` looks identical to one
correctly wired to Vault, and the difference is the entire point of the step.

Stop.

---

# Final Rule

Never continue to another step automatically.

Wait for the next instruction from the user.

Never skip a step.

Never merge multiple steps together unless explicitly instructed.
