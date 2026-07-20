# docker

Everything about **how containers are built and launched**. Component configuration lives separately
under [`infrastructure/`](../infrastructure) and is mounted into containers from there.

## Layout

```
docker/
├── compose/
│   ├── docker-compose.yml               [step 02] databases, cache, broker, storage
│   ├── docker-compose.platform.yml      [step 02] gateway, proxy, auth, discovery
│   ├── .env.example                     [step 02] template for every required variable
│   ├── docker-compose.observability.yml [step 10-13] logs, metrics, traces, profiles
│   └── docker-compose.services.yml      [step 09] the two Spring Boot services
├── order-service/
│   └── Dockerfile                       [step 09] multi-stage, non-root, JDK 21 base
└── inventory-service/
    └── Dockerfile                       [step 09] multi-stage, non-root, JDK 21 base
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

## Running the stack

`.env` is git-ignored and is created from `.env.example` on first run, so plain `docker compose`
works from this directory once it exists. The wrapper handles that and the health waiting:

```bash
../scripts/infra.sh up        # start and wait until every container is healthy
../scripts/infra.sh health    # one line per container
../scripts/infra.sh destroy   # stop and delete all volumes
```

`COMPOSE_FILE` in `.env` combines the compose files, which is why they are never passed with `-f`.

Full operational detail — network topology, init scripts, healthcheck timings, troubleshooting — is
in [docs/Infrastructure.md](../docs/Infrastructure.md).

## Status

The two compose files above exist as of **step 02** and bring up all ten infrastructure components.
Service Dockerfiles and the observability stack arrive with the steps marked in the layout — see the
roadmap in the [root README](../README.md).
