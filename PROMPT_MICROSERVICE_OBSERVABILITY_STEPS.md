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

## Documentation

Generate complete documentation.

Include

Deployment Guide

Runbook

Architecture

Observability Guide

Logging Guide

Metrics Guide

Tracing Guide

Profiling Guide

Troubleshooting Guide

Performance Guide

Security Guide

Sequence Diagrams

Infrastructure Diagram

Final README

Stop.

---

# Final Rule

Never continue to another step automatically.

Wait for the next instruction from the user.

Never skip a step.

Never merge multiple steps together unless explicitly instructed.
