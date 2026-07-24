# infrastructure

Configuration for every platform and observability component the lab runs. This directory holds
**configuration only** — no Dockerfiles and no Compose files, which live under [`docker/`](../docker).
Keeping the two apart means a component's configuration can be read, reviewed and version-controlled
without untangling it from how the container is launched.

## Layout

Each component owns one directory, named exactly as the component is named in Compose, so a running
container can always be traced back to its configuration in one step.

Directories marked `[step nn]` arrive with the step that needs them.

```
infrastructure/
├── nginx/                  nginx.conf + conf.d: JSON access log, /healthz, upstreams [step 06]
├── kong/                   kong.yml: DB-less declarative config; routes and plugins  [step 06]
├── keycloak/               realm export: clients, roles, users                       [step 07]
├── consul/                 config/server.hcl; KV seed                                [step 08]
├── vault/                  config/vault.hcl; policies/ one per service               [step 20]
├── postgres/               init/: application databases, owning roles, monitor role
├── oracle/                 init/: tablespace quota, schema privileges, monitor user
│                           exporter-metrics.toml: custom V$ metrics                  [step 16]
├── kafka/                  init/: topic declarations with retention and partitions
├── redis/                  redis.conf: eviction policy, persistence, slowlog
├── minio/                  init/: bucket, versioning, least-privilege user
│
│                           ── observability ──                              [steps 10-16]
├── otel-collector/         receivers, processors and the fan-out to every backend
├── prometheus/             prometheus.yml, and rules/ split one file per subsystem
├── alertmanager/           routing tree, receivers, inhibition; templates/            [step 16]
├── grafana/                provisioned datasources, dashboards and alerting/
├── loki/                   log store configuration
├── tempo/                  trace store configuration
├── pyroscope/              continuous profiling server configuration
├── fluent-bit/             log shipping to the collector
├── fluentd/                log shipping to OpenSearch
├── promtail/               log shipping straight to Loki
│
│                           ── simulation ──
├── toxiproxy/              toxiproxy.json: the seven proxies, and no toxics by default
└── k6/                     load scenarios: smoke, load, stress, spike, soak
```

The observability and simulation components sit at the top level rather than nested under a parent
directory: Compose names each container after its directory, and one level of nesting for a grouping
that only exists in prose would break that correspondence.

`k6/` is the one directory that holds code rather than configuration. It earns its place here for the
same reason as the rest: it is mounted into a container that is launched from `docker/`, and the
scenarios are read and reviewed as configuration of what "high load" means for this system.

## Rules for anything added here

- **No secrets.** Passwords, tokens and keys are injected from the environment at container start.
  Files here may reference a variable, never its value.
- **Configuration is declarative and reloadable.** Prefer a config file the component reads over a
  setup performed by an imperative script.
- **One directory per component.** A component that needs several files gets subdirectories, not a
  shared dumping ground.
- **Comment the intent.** These files are read by people learning the stack; explain why a setting is
  what it is, not merely what it sets.

## Init scripts

`postgres/`, `oracle/`, `kafka/` and `minio/` each carry an `init/` directory that is mounted into
the corresponding container and executed **once**, against empty state. They are idempotent where the
tooling allows it, and they read every credential from the environment.

Re-running them means deleting the volume: `../scripts/infra.sh destroy`.

**This bites when a step adds one.** Step 16 added the read-only monitoring roles the database
exporters use; on a stack whose volumes already existed, those roles are simply absent and the
exporter reports the database as down. The symptom points at the database, and the cause is that an
init script never ran. [docs/Alerting.md §7](../docs/Alerting.md) has the statements to create them
by hand.

## Status

As of **step 16**, every directory above is populated and the stack comes up healthy: the data plane,
the edge, identity and discovery, the four telemetry pipelines, and the alerting chain that turns a
firing rule into a notification somebody receives.

See the roadmap in the [root README](../README.md), [docs/Infrastructure.md](../docs/Infrastructure.md)
for how the stack is operated, and [docs/Alerting.md](../docs/Alerting.md) for what fires and where
it goes.
