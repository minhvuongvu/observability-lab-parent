# docker

Everything about **how containers are built and launched**. Component configuration lives separately
under [`infrastructure/`](../infrastructure) and is mounted into containers from there.

## Layout

```
docker/
├── compose/
│   ├── docker-compose.yml               databases, cache, broker, storage — declares lab-net
│   ├── docker-compose.platform.yml      gateway, proxy, auth, discovery
│   ├── docker-compose.observability.yml logs, metrics, traces, profiles, alerting
│   ├── docker-compose.services.yml      the two Spring Boot services — declares lab-logs
│   ├── docker-compose.simulation.yml    Toxiproxy and k6
│   └── .env.example                     template for every required variable
├── service/
│   └── Dockerfile                       builds either service; multi-stage, non-root, JRE 21
└── fluentd/
    └── Dockerfile                       Fluentd with the OpenSearch output plugin
```

The Compose definition is split by concern rather than kept in one file. The stack is large, and
splitting it keeps each file small enough to read end to end.

They are split for readability, **not** so they can be run separately — `COMPOSE_FILE` in `.env`
always combines all five. Keycloak needs the PostgreSQL service from the core file, the services need
Toxiproxy from the simulation file, and `lab-net` itself is declared in the core file. Compose
profiles (`search`, `load`) are what actually make components opt-in.

### One Dockerfile, not two

`docker/service/Dockerfile` takes `--build-arg SERVICE` and builds either service. The two would
otherwise be identical apart from a module name and a port number, and two copies of sixty lines are
two copies that drift — the drift showing up as one service quietly running a different JDK or agent
version than the other, which is the last thing anyone suspects while comparing their traces.

It also means both images share every layer up to the source copy, so the second builds almost free.

**The build context is the repository root.** The Maven reactor is multi-module: each service
compiles against `shared-library` and against stubs generated from `proto/`, so a context scoped to
one service directory cannot build it. `.dockerignore` at the root keeps that context small.

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

## The network

**One network, `lab-net`.** Every container joins it and nothing runs outside it — not the services,
not the load generator, not the fault proxy.

This replaced four tier networks. The reasoning, and an honest account of the isolation that was
given up, is in the `networks:` block of `docker-compose.yml` and in
[docs/Infrastructure.md](../docs/Infrastructure.md).

Three addressing rules follow from it:

| Rule | Why |
| --- | --- |
| Components address each other **by compose name** | A published port can then change without breaking anything but a bookmark |
| Applications reach dependencies **through `toxiproxy`** | Faults become injectable at runtime; with no toxics it is a transparent relay |
| Monitoring reaches its targets **directly** | An exporter sharing the application's broken path could not tell you the path is what is broken |

## Running the stack

`.env` is git-ignored and is created from `.env.example` on first run, so plain `docker compose`
works from this directory once it exists. The wrapper handles that, the build and the health waiting:

```bash
../scripts/infra.sh up        # build, start, wait until every container is healthy
../scripts/infra.sh build     # rebuild just the two service images
../scripts/infra.sh health    # one line per container
../scripts/infra.sh destroy   # stop and delete all volumes
```

`COMPOSE_FILE` in `.env` combines the compose files, which is why they are never passed with `-f`.

Load and faults are driven separately, once the stack is up:

```bash
../scripts/load.sh  load                 # sustained load from inside the network (10 orders/s)
../scripts/chaos.sh slow postgres 400    # latency on the database hop
../scripts/chaos.sh reset                # undo every fault
```

Full operational detail — the single network and what it cost, init scripts, healthcheck timings,
troubleshooting — is in [docs/Infrastructure.md](../docs/Infrastructure.md). Simulation scenarios,
with the signal each should produce, are in [docs/Simulation.md](../docs/Simulation.md).
