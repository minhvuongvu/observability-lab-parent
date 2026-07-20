# Microservice Observability Lab Generator

## Role

You are a Principal Software Architect, Staff Backend Engineer, Platform Engineer, DevOps Engineer, and SRE.

Your task is NOT simply generating source code.

Your task is to build an enterprise-grade learning platform that simulates how a real production microservice system is designed, deployed, observed and operated.

This project is used for learning:

- Distributed System
- Spring Boot Microservices
- Cloud Native Architecture
- Logging
- Metrics
- Tracing
- Profiling
- Monitoring
- Service Discovery
- API Gateway
- Authentication
- Event-driven Architecture
- Containerized Infrastructure

This is NOT a CRUD demo.

Business logic is intentionally simple.

Technology and architecture must closely resemble a real production system.

---

# Primary Goal

Generate an entire repository that demonstrates modern enterprise microservice architecture with complete observability.

Every technology should have a meaningful reason to exist.

Every component should communicate like a real production environment.

No fake or isolated demo.

Every service should continuously produce logs, metrics, traces and events.

---

# Tech Stack

## Backend

- Java 21
- Spring Boot 3.x
- Maven

Use:

- Spring Web
- Spring Data JPA
- Spring Validation
- Spring Security
- Spring Boot Actuator
- Micrometer
- OpenTelemetry SDK
- OpenFeign
- Spring Kafka
- Redis
- PostgreSQL Driver
- Oracle Driver
- Logback
- SLF4J

---

# Infrastructure

Generate docker-compose for all infrastructure.

Infrastructure includes

API Gateway

- Kong Gateway

Reverse Proxy

- Nginx

Authentication

- Keycloak

Service Discovery

- Consul

Configuration

- Consul KV

Message Broker

- Apache Kafka
- Kafka UI

Cache

- Redis

Object Storage

- MinIO

Databases

- PostgreSQL
- Oracle XE

Observability

Logs

- Fluent Bit
- Fluentd
- Promtail
- OpenTelemetry Collector
- Loki
- OpenSearch
- Elasticsearch
- Grafana
- Kibana

Metrics

- Prometheus
- VictoriaMetrics
- Grafana

Tracing

- OpenTelemetry Collector
- Tempo
- Jaeger
- Zipkin
- Grafana

Profiling

- Pyroscope Agent
- Pyroscope
- Grafana

---

# Microservices

Build two Spring Boot services.

Service A

Order Service

Database

PostgreSQL

Service B

Inventory Service

Database

Oracle

Communication

REST

Feign Client

Kafka Event

---

# Architecture

Client

↓

Nginx

↓

Kong Gateway

↓

Keycloak Authentication

↓

Microservices

↓

Database

↓

Kafka

↓

Other services

↓

Observability Stack

Every request should go through the full chain.

Never bypass gateway unless required internally.

---

# Production Simulation

The project should simulate production.

Include

API Gateway

Authentication

Authorization

Service Discovery

Externalized Config

Database

Caching

Async Messaging

Object Storage

Centralized Logging

Distributed Tracing

Metrics

Profiling

Health Check

Container Networking

Startup Dependency

Graceful Shutdown

Retry

Timeout

Circuit Breaker

Correlation ID

Request ID

Trace ID

Span ID

MDC Logging

Structured JSON Logs

---

# Business Flow

Keep business extremely simple.

Example

Create Order

↓

Validate Token

↓

Gateway

↓

Order Service

↓

Save PostgreSQL

↓

Upload invoice to MinIO

↓

Publish Kafka Event

↓

Inventory Service

↓

Update Oracle

↓

Publish another Event

↓

Order Service

↓

Return Response

This flow must generate

REST calls

Database access

Kafka events

Cache access

Object Storage access

Distributed trace

Logs

Metrics

Profiling samples

---

# Logging

Use

SLF4J

Logback

JSON Log

MDC

Every request should contain

Trace ID

Span ID

Request ID

User ID

Service Name

Environment

Version

Correlation ID

Generate

INFO

WARN

ERROR

DEBUG

Different exception types

Validation exception

Business exception

Unexpected exception

Database exception

Kafka exception

Feign timeout

Retry logs

---

# Log Pipeline

Application

↓

Fluent Bit

↓

OpenTelemetry Collector

↓

Loki

↓

Grafana

AND

Application

↓

Fluentd

↓

OpenSearch

↓

OpenSearch Dashboard (optional)

AND

Application

↓

Promtail

↓

Loki

Demonstrate all pipelines.

---

# Metrics

Expose

/actuator/prometheus

Use Micrometer.

Collect

HTTP

JVM

CPU

Memory

GC

Threads

Connection Pool

Kafka

Redis

Feign

Database

Custom Metrics

Business Metrics

Counters

Timers

Gauges

Distribution Summary

Long Task Timer

Prometheus scrapes metrics.

VictoriaMetrics also stores metrics.

Grafana visualizes them.

---

# Tracing

Use

OpenTelemetry SDK

Auto Instrumentation where possible

Instrument manually where needed.

Export

OTLP

Collector

Collector exports simultaneously to

Tempo

Jaeger

Zipkin

Trace should include

Gateway

Order Service

Inventory Service

Kafka Producer

Kafka Consumer

Redis

Database

Feign

MinIO

Every span should have

Attributes

Events

Status

Exception

Links

---

# Profiling

Use

Pyroscope Agent

Pyroscope Server

Generate CPU profiles

Allocation profiles

Lock contention

Heap

Integrate Grafana.

---

# Kafka

Use realistic event-driven architecture.

Topics

order-created

inventory-updated

dead-letter-topic

retry-topic

Include

Consumer Group

Retry

DLQ

Backoff

Idempotency example

---

# Redis

Use

Caching

Distributed Lock example

Rate Limit example

TTL

Eviction

---

# Keycloak

Realm

Demo Realm

Clients

Gateway

Swagger

Frontend placeholder

Roles

ADMIN

USER

Generate JWT flow.

---

# Kong Gateway

Configure

JWT Validation

Rate Limiting

Routing

Plugins

---

# Consul

Register all services automatically.

Store configuration inside Consul KV.

Spring Cloud Consul integration.

---

# MinIO

Store uploaded invoice.

Generate signed URL.

---

# OpenTelemetry Collector

Receive

Logs

Metrics

Traces

Export to all backends.

Include collector configuration.

---

# Docker

Every infrastructure component must run in Docker.

Every service must be containerized.

Use Docker Compose.

Create

healthcheck

depends_on

named networks

persistent volumes

resource limits

restart policy

---

# Repository Structure

Generate clean enterprise repository.

Example

root

/docs

/docker

/docker-compose

/infrastructure

/services

/order-service

/inventory-service

/shared-library

/observability

/scripts

/postman

/grafana

/prometheus

/loki

/tempo

/jaeger

/zipkin

/fluent-bit

/fluentd

/promtail

/otel-collector

/kong

/nginx

/keycloak

/consul

/minio

/redis

/kafka

/postgres

/oracle

---

# Documentation

Generate detailed documentation.

Architecture.md

SystemDesign.md

Deployment.md

Observability.md

Logging.md

Metrics.md

Tracing.md

Profiling.md

Kafka.md

Redis.md

Gateway.md

Keycloak.md

Consul.md

MinIO.md

Runbook.md

Troubleshooting.md

Performance.md

Security.md

---

# Sequence Diagrams

Generate Mermaid diagrams for

Login

API Request

Kafka Flow

Tracing Flow

Logging Flow

Metrics Collection

Profiling

Startup

Shutdown

---

# Architecture Diagram

Generate Mermaid architecture diagram.

---

# Dashboard

Prepare Grafana dashboards for

JVM

HTTP

Kafka

Redis

Database

Business Metrics

Logs

Traces

Profiles

---

# Failure Simulation

Create endpoints that intentionally generate

Slow request

Timeout

Database failure

Kafka failure

Redis failure

Memory leak simulation

CPU spike

Exception

Deadlock

Large log burst

High traffic

Retry

Circuit breaker

These endpoints are required for observability learning.

---

# Code Quality

Apply

Clean Architecture

DDD-lite

SOLID

12-Factor App

Production-ready configuration

Meaningful package structure

Configuration separation

Profiles

Local

Dev

Prod

---

# Final Deliverable

Generate the complete project.

Do not stop after generating architecture.

Implement everything.

Whenever the output becomes too large, continue automatically from the previous point.

Never summarize.

Always continue generating code until the repository is complete.
