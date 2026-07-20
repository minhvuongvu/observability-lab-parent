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
├── kong/                   kong.yml: DB-less declarative config; routes and plugins [step 06]
├── keycloak/               realm export: clients, roles, users                       [step 07]
├── consul/                 config/server.hcl; KV seed                                [step 08]
├── postgres/               init/: per-application databases and owning roles
├── oracle/                 init/: tablespace quota and schema privileges
├── kafka/                  init/: topic declarations with retention and partitions
├── redis/                  redis.conf: eviction policy, persistence, slowlog
├── minio/                  init/: bucket, versioning, least-privilege user
└── observability/                                                              [step 10-13]
    ├── otel-collector/     receivers, processors and the fan-out to every backend
    ├── prometheus/         scrape configuration and recording rules
    ├── victoriametrics/    long-term metric storage settings
    ├── grafana/            provisioned datasources and dashboards
    ├── loki/               log store configuration
    ├── tempo/              trace store configuration
    ├── jaeger/             trace UI and storage configuration
    ├── zipkin/             trace UI configuration
    ├── pyroscope/          continuous profiling server configuration
    ├── fluent-bit/         log shipping to the collector
    ├── fluentd/            log shipping to OpenSearch
    ├── promtail/           log shipping straight to Loki
    ├── opensearch/         log index settings
    └── elasticsearch/      log index settings
```

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

## Status

As of **step 02**, the six configuration and init directories above are populated and the stack comes
up healthy. Gateway routing, the Keycloak realm, Consul KV and the observability configuration arrive
with the steps marked in the layout — see the roadmap in the [root README](../README.md), and
[docs/Infrastructure.md](../docs/Infrastructure.md) for how the stack is operated.
