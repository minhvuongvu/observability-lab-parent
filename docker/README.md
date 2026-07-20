# docker

Everything about **how containers are built and launched**. Component configuration lives separately
under [`infrastructure/`](../infrastructure) and is mounted into containers from there.

## Layout

```
docker/
├── order-service/
│   └── Dockerfile          multi-stage build, non-root runtime, JDK 21 base
├── inventory-service/
│   └── Dockerfile          multi-stage build, non-root runtime, JDK 21 base
└── compose/
    ├── docker-compose.yml               core: databases, cache, broker, storage
    ├── docker-compose.platform.yml      gateway, proxy, auth, discovery
    ├── docker-compose.observability.yml logs, metrics, traces, profiles
    ├── docker-compose.services.yml      the two Spring Boot services
    └── .env.example                     documented template for required variables
```

The Compose definition is split by concern rather than kept in one file. The stack is large, and
splitting it means a learner can bring up only the slice they are studying — for example core plus
observability, without the gateway — and it keeps each file small enough to read end to end.

## Conventions for containers in this project

- **Multi-stage builds.** Dependencies resolve in a builder stage so application changes do not
  invalidate the dependency layer.
- **Non-root runtime.** Every image runs as an unprivileged user.
- **Healthchecks everywhere.** A container is not "up" until it is actually serving.
- **`depends_on` with `condition: service_healthy`.** Startup order is expressed as a real dependency,
  not a sleep.
- **Named networks and named volumes.** No anonymous volumes, no default bridge.
- **Resource limits and restart policies.** The stack behaves like a constrained production host, so
  memory pressure and restarts are observable rather than hidden.
- **Secrets from the environment.** `.env` is git-ignored; `.env.example` documents every variable.

## Status

Populated in **step 02**. Step 01 establishes the repository skeleton only — see the roadmap in the
[root README](../README.md).
