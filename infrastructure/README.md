# infrastructure

Configuration for every platform and observability component the lab runs. This directory holds
**configuration only** — no Dockerfiles and no Compose files, which live under [`docker/`](../docker).
Keeping the two apart means a component's configuration can be read, reviewed and version-controlled
without untangling it from how the container is launched.

## Layout

Each component owns one directory, named exactly as the component is named in Compose, so a running
container can always be traced back to its configuration in one step.

```
infrastructure/
├── nginx/                  reverse proxy: upstreams, TLS termination, access log format
├── kong/                   declarative gateway config: routes, services, plugins
├── keycloak/               realm export: clients, roles, users
├── consul/                 agent config and the KV seed for externalised configuration
├── postgres/               init SQL for the Order Service schema
├── oracle/                 init SQL for the Inventory Service schema
├── kafka/                  broker settings and topic bootstrap
├── redis/                  redis.conf: eviction policy, persistence, memory limits
├── minio/                  bucket and policy bootstrap
└── observability/
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

## Status

Populated from **step 02** onwards. Step 01 establishes the repository skeleton only — see the
roadmap in the [root README](../README.md).
