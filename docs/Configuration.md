# Configuration

Every knob that matters, where it lives, what changes when it moves, and which ones you must move
together.

> **Read §2 first if you read nothing else.** The distinction between a *published port* and an
> *in-network address* is the single most common source of confusion in this stack, and most
> configuration mistakes here are really that mistake wearing a different hat.
>
> Effective values below were read from the running stack, not from the files alone.

---

## 1. Where configuration lives

Four layers, later wins:

```
1. application.yml            in the jar — defaults describing a standard local stack
2. application-{profile}.yml  local | dev | prod
3. Consul KV                  config/application/data — watched, live, no restart
4. Environment variables      from docker/compose/.env, via the compose files
```

| File | Owns | Tracked in git |
| --- | --- | --- |
| `docker/compose/.env` | **Every** deployment variable: ports, credentials, image pins, limits | **No** |
| `docker/compose/.env.example` | The template and the list of every variable that exists | Yes |
| `services/*/src/main/resources/application*.yml` | Application behaviour: pools, timeouts, deadlines, retries | Yes |
| `infrastructure/<component>/` | What each component does: Kong routes, alert rules, dashboards, Loki | Yes |
| `infrastructure/consul/kv/application.yaml` | Shared runtime overrides, seeded into Consul KV | Yes |

**Never edit a compose file to change a value.** Every value in them is `${A_VARIABLE}`, and the
variable belongs in `.env`. That keeps your machine's overrides out of version control.

### Proving layer 3 is real

```bash
curl -s http://localhost:8081/actuator/info
```

```json
{"platform": {"config-source": "Consul KV (config/application/data)"}}
```

The bundled `application.yml` sets that string to `"application.yml default (Consul KV not applied)"`.
Seeing the other value is the visible proof that external configuration was loaded. Change it live:

```bash
docker exec -i lab-consul consul kv put config/application/data - < infrastructure/consul/kv/application.yaml
```

Both services `watch` the key and re-bind within seconds. No restart.

---

## 2. Published port versus in-network address

> **A published port exists for a person.** A browser opening Grafana, a `curl` against an API, `psql`
> from your shell.
>
> **Nothing inside the stack uses one.** Every component addresses every other by its compose name on
> `lab-net`.

The consequence, and it is liberating: **changing a published port can inconvenience you and cannot
break the system.**

### The pairs that trip everyone

| Variable | It is | It is **not** |
| --- | --- | --- |
| `POSTGRES_PORT=5432` | Host publication, for `psql` | What the Order Service connects to |
| `ORDER_DB_PORT=15432` | **Toxiproxy's in-network listener** for PostgreSQL | A PostgreSQL port |
| `REDIS_HOST_PORT=6379` | Host publication, for `redis-cli` | What the services use |
| `REDIS_PORT=16379` | **Toxiproxy's in-network listener** for Redis | A Redis port |
| `KAFKA_EXTERNAL_PORT=29092` | Host publication, for an IDE-run service | What containers use (`kafka:9092`) |
| `TOXIPROXY_KAFKA_PORT=19092` | The proxy's Kafka listener, advertised by the broker | A broker port |

So the Order Service's database URL resolves to `toxiproxy:15432`, and Toxiproxy forwards to
`postgres:5432`. Both numbers are correct and neither is "the PostgreSQL port".

### Taking the fault proxy out of a path

Point the variable at the real service name. One line, one dependency:

```bash
# in docker/compose/.env
ORDER_DB_HOST=postgres
ORDER_DB_PORT=5432
```

| Variable | Through the proxy (default) | Direct |
| --- | --- | --- |
| `ORDER_DB_HOST` / `ORDER_DB_PORT` | `toxiproxy` / `15432` | `postgres` / `5432` |
| `ORACLE_DB_HOST` / `ORACLE_DB_PORT` | `toxiproxy` / `11521` | `oracle` / `1521` |
| `REDIS_HOST` / `REDIS_PORT` | `toxiproxy` / `16379` | `redis` / `6379` |
| `MINIO_ENDPOINT` | `http://toxiproxy:19000` | `http://minio:9000` |
| `KAFKA_BOOTSTRAP_SERVERS` | `toxiproxy:19092` | `kafka:9092` |
| `INVENTORY_ADVERTISE_HOST` | `toxiproxy` | `inventory-service` |

Going direct costs you the ability to inject faults on that hop and buys a slightly shorter path. It
is the right move when you are measuring raw latency and want the relay out of the numbers.

**Kafka is special.** If you point `KAFKA_BOOTSTRAP_SERVERS` at `kafka:9092`, no Kafka toxic can ever
affect that client — a client bootstrapping on the internal listener is told to keep using
`kafka:9092`, and it silently routes past the proxy. That is why the broker has a separate `PROXY`
listener at all.

---

## 3. The load-bearing values

These four decide what this lab *demonstrates*. Change one and the measured behaviour changes with it.

| Variable | Default | What it sets |
| --- | --- | --- |
| `ORDER_SERVICE_CPUS` | `1.5` | Where CPU saturation begins |
| `ORDER_SERVICE_MEMORY` | `768M` | Container ceiling — and the JVM heap, at 70% of it |
| `INVENTORY_SERVICE_CPUS` / `_MEMORY` | `1.5` / `768M` | Same, for the other service |
| `spring.datasource.hikari.maximum-pool-size` | `10` | **The binding constraint on throughput** |

> **The k6 defaults are calibrated against these numbers.** The lab saturates at around 10 orders/s
> because a 10-connection pool with ~50 ms of database work per request cannot sustain more. Raise the
> pool or the CPU limit and `load.js`'s `RATE=10` is no longer the rate that "runs clean" — it is just
> a small number.
>
> Raising a limit to watch a symptom disappear is a legitimate experiment. Raising it because a run
> went red is deleting the result. Either way, **re-measure before comparing to any number in
> [Performance.md §2](Performance.md#2-the-measured-ceiling)**.

The heap follows the container limit via `-XX:MaxRAMPercentage=70`, never `-Xmx`, so the two cannot
drift apart. Change `ORDER_SERVICE_MEMORY` and the heap changes with it.

---

## 4. Timeouts and deadlines

The rule, stated once:

> **A callee's budget must be smaller than its caller's.** Otherwise the callee's timeout never fires,
> the caller times out first, and the more specific error is lost.

```
Nginx        proxy_read_timeout 20s     infrastructure/nginx/conf.d/default.conf
└─ Kong      read_timeout       15s     infrastructure/kong/kong.yml
   └─ Order Service HTTP budget  3000ms
      ├─ order handler           1000ms
      │  └─ gRPC BatchCheckStock  300ms  app.grpc.client.inventory.batch-check-deadline
      │     └─ Oracle query       250ms
      ├─ Hikari connection-timeout 3000ms
      ├─ Redis timeout             2000ms
      └─ Feign read-timeout        5000ms
```

Nginx is deliberately **longer** than Kong so a slow upstream is reported by the component that knows
why, rather than cut off at the edge.

| Knob | Where | Raising it |
| --- | --- | --- |
| `check-stock-deadline` (200 ms) | `application.yml` | Fewer `DEADLINE_EXCEEDED`, but a slow dependency now consumes the caller's budget |
| `batch-check-deadline` (300 ms) | same | same |
| `reserve-stock-deadline` (200 ms) | same | This one is an *optimisation*. Missing it costs nothing but a fall-through to Kafka — keep it tight |
| Hikari `connection-timeout` (3 s) | same | Threads pile up behind an unreachable database instead of failing fast |
| Kong `read_timeout` (15 s) | `kong.yml` | Must stay below Nginx's 20 s |

---

## 5. Retention, TTLs and sampling — and what each costs

**Verified against the running stack**, because several of these differ from the design targets in
[SystemDesign.md §11](SystemDesign.md#11-non-functional-targets).

| Setting | Effective value | Where | Cost of raising it |
| --- | --- | --- | --- |
| **Prometheus retention** | **1 day** (`--storage.tsdb.retention.time=24h`) | compose | Disk in `lab-prometheus-data`. The design target is 15 days |
| **VictoriaMetrics retention** | **30 days** (`--retentionPeriod=30d`) | compose | Disk. The design target is 90 days |
| Loki retention | 7 days (`retention_period: 168h`) | `infrastructure/loki/loki.yml` | Disk |
| Tempo block retention | 7 days (`block_retention: 168h`) | `infrastructure/tempo/tempo.yml` | Disk |
| Scrape interval | 15 s; **10 s** for the services; **30 s** for Oracle | `prometheus.yml` | Series volume, and load on each target. Oracle's views are expensive, hence 30 s |
| Trace sampling | **100%** (`parentbased_always_on`) | compose env | CPU and OTLP volume. Correct for a lab, wrong for production |
| Profile upload | every 10 s, `itimer` + alloc 512k + lock 10ms | compose env | Continuous CPU cost |
| Orders cache TTL | 10 min (`app.cache.orders-ttl`) | `application.yml` | Staleness. Short enough that a stale order is an annoyance, not a correctness problem |
| Invoice URL validity | 15 min (`app.invoice.url-validity`) | `application.yml` | A leaked URL works for longer |
| Outbox retention | 24 h (`app.outbox.retention`) | `application.yml` | Table grows with uptime rather than traffic |
| Access token lifespan | 300 s | Keycloak realm | A stolen token is useful for longer |
| Chaos toggle TTL | 5 min default, 30 min max | `app.chaos.*` | A forgotten fault lasts longer |

**Prometheus keeps one day.** That is the one to remember: an incident from the day before yesterday
is not in Prometheus, it is in VictoriaMetrics — which is exactly why every sample is `remote_write`n
there. Both answer the same PromQL, so the query does not change, only the datasource.

### Sampling is the one to leave alone

`OTEL_TRACES_SAMPLER=parentbased_always_on` means every request is traced. In production that is a
budget problem; here it is the point — a lab where the interesting request was the one that got
sampled away teaches nothing.

---

## 6. Concurrency and batching

| Knob | Default | Notes |
| --- | --- | --- |
| Kafka listener `concurrency` | 3 | Matches the **3 partitions** exactly — one thread per partition. Raising it alone does nothing |
| `KAFKA_TOPIC_PARTITIONS` | 3 | Only applied when a topic is *created*, i.e. on an empty broker volume |
| `max-poll-records` | 50 | Larger batches mean longer between commits, so a crash replays more |
| Hibernate `jdbc.batch_size` | 25 | Fewer round trips on multi-row writes; longer transactions |
| `max-page-size` | 100 | A ceiling on what a caller may ask for. Without it `size=1000000` loads the table into memory |
| Kong rate limit | 120/min, 3000/hour per route | `policy: local`; correct only because there is one Kong node |
| Body size limit | 1 MB at Nginx **and** Kong | The outermost hop is the cheapest place to refuse a body |
| `K6_SKU_COUNT` | 20 | Deliberately low, so orders contend on the same Oracle rows and produce lock contention worth profiling |

---

## 7. Changes that only apply to an empty volume

Some things are provisioned by an init script that runs **only when its volume is empty**. Changing
the variable on a stack that already has data does nothing, silently.

| Change | Applies |
| --- | --- |
| `ORDER_DB_USER` / `_PASSWORD`, `MONITOR_DB_USER` / `_PASSWORD` | Only if `lab-postgres-data` is empty |
| `INVENTORY_DB_USER` / `_PASSWORD`, `ORACLE_MONITOR_USER` / `_PASSWORD` | Only if `lab-oracle-data` is empty |
| Keycloak realm JSON | Only if the realm does not exist. Log says `Realm 'observability' already exists. Import skipped` |
| `KAFKA_TOPIC_PARTITIONS` | Only for topics that do not exist yet |
| MinIO bucket and app user | **Every start** — `minio-init` is idempotent |
| Kafka topic *existence* | **Every start** — `kafka-init` is idempotent |
| Consul KV | **Every start**, and live via `consul kv put` |

```bash
./scripts/infra.sh destroy && ./scripts/infra.sh up    # the blunt fix
```

`docs/Alerting.md §7` has the by-hand SQL for the monitoring roles when destroying is not acceptable.

---

## 8. What must change together

Change one of these without the other and the system misbehaves — usually silently.

| If you change | You must also change | Otherwise |
| --- | --- | --- |
| `KEYCLOAK_REALM` or `KEYCLOAK_ISSUER` | The consumer `key` in `infrastructure/kong/kong.yml` | Every token is rejected as an unknown issuer — a 401 that looks like an authorization bug |
| The realm's RSA signing key | `rsa_public_key` in `kong.yml` | Same |
| `TOXIPROXY_KAFKA_PORT` | The `PROXY` advertised listener in `docker-compose.yml` **and** the `kafka` proxy in `toxiproxy.json` | Clients silently route *past* the proxy; Kafka faults appear to do nothing |
| `ORDER_SERVICE_CPUS` / `_MEMORY` | Your expectations, and the k6 `RATE` | The calibrated defaults no longer describe anything |
| `KAFKA_CLUSTER_ID` | Destroy `lab-kafka-data` | A KRaft log directory is stamped with the id that formatted it; the broker refuses to mount its own data |
| Kong `read_timeout` upward | Nginx `proxy_read_timeout` | The edge cuts off before the component that knows why |
| A JPA entity | A Flyway migration | `ddl-auto: validate` turns drift into a startup failure — by design |
| `INVENTORY_ADVERTISE_HOST` | Nothing, but know what you lose | Set to `inventory-service` and the service-to-service hop can no longer be faulted |

### The staleness the tooling cannot catch

`infra.sh` diffs `.env` against `.env.example` and warns about **missing** keys. It cannot warn about
keys that are present but whose *meaning* changed. Two have:

| Key | Used to mean | Now means |
| --- | --- | --- |
| `REDIS_PORT` | The published host port | The in-network Toxiproxy listener (`16379`); publication moved to `REDIS_HOST_PORT` |
| `KEYCLOAK_ISSUER` | `http://localhost:8080/…` | `http://keycloak:8080/…`, matching Kong's pinned key |

A `.env` from before the containerisation change carries both, passes the check, and produces a stack
that starts and 401s everything.

```bash
cd docker/compose
cp .env ".env.stale-$(date +%Y%m%d-%H%M%S)"
cp .env.example .env
# re-apply your own overrides — a remapped published port is the usual one
```

---

## 9. What takes effect without a restart

| Change | How | Verify |
| --- | --- | --- |
| Kong routes, plugins, rate limits | `./scripts/gateway.sh validate && ./scripts/gateway.sh reload` | `./scripts/gateway.sh routes` |
| Nginx headers, timeouts | same command — it reloads both | `curl -i http://localhost/healthz` |
| Shared app config | `consul kv put config/application/data` | `curl -s http://localhost:8081/actuator/info` |
| Prometheus rules | `curl -X POST http://localhost:9090/-/reload` | `http://localhost:9090/rules` |
| Alertmanager routing | `curl -X POST http://localhost:9093/-/reload` | Mailpit at :8025 |
| Grafana dashboards, datasources, alert rules | Re-read from the mounted directories on an interval | Reload the dashboard |
| Network faults | `./scripts/chaos.sh …` — HTTP API | `./scripts/chaos.sh list` |
| In-process chaos toggles | `./scripts/chaos.sh app …` | `./scripts/chaos.sh app status` |
| **Anything in `.env` or a service's `application.yml`** | **Rebuild and recreate the container** | — |

**Gateway reloads are asynchronous.** `kong reload` returns once the master process is signalled;
workers respawn behind it and the old config serves for a short window. `gateway.sh reload` waits for
`configuration_hash` to change before returning, precisely so a test immediately afterwards does not
exercise the old configuration and conclude the change did not work.

---

## 10. Profiles

| Profile | Intent | Notable differences |
| --- | --- | --- |
| `local` | A workstation | Human-readable console; JSON only to the file. Chaos endpoints **on** |
| `dev` (containers use this) | Shared integration | JSON to **both** console and file, so `docker logs` shows what the agents ship. Chaos **on** |
| `prod` | Conservative | Chaos endpoints **absent**; Swagger and `/v3/api-docs` **off**; no stack traces to callers; `health.show-details: never`; pool 20/5; root log level `WARN` |

`SERVICE_PROFILE` in `.env` selects it; containers default to `dev`.

**`prod` is the switch that removes the lab's teaching affordances.** Flipping it is the fastest way
to see what an artefact of this system would actually expose — and the fastest sanity check that the
chaos endpoints are genuinely profile-gated rather than merely role-gated.

---

## 11. Credentials

Every credential is a variable in `.env`, which is git-ignored. `.env.example` is tracked and carries
obvious placeholders that must never become real values.

| Account | Scope |
| --- | --- |
| `order_user` | `orderdb` only |
| `keycloak_user` | `keycloakdb` only — neither can read the other's data |
| `inventory_user` | The Oracle PDB app schema |
| `MONITOR_DB_USER`, `ORACLE_MONITOR_USER` | **Read-only**, for the exporters |
| `MINIO_APP_USER` | The invoice bucket only. MinIO root is never used by a service |

**Two configuration files deliberately do not hold secrets:**

- `infrastructure/redis/redis.conf` keeps the *policy* (`maxmemory`, eviction, persistence); the
  password arrives as a command-line argument from the environment, which overrides the file. A config
  file in a repository must never contain a credential.
- `infrastructure/alertmanager/alertmanager.yml` is the opposite case and equally deliberate:
  Alertmanager does **not** expand environment variables, so a value set in `.env` would be silently
  ignored while looking configured. Recipients live in the file, which is where a reviewer looks.

The full posture, including what is deliberately unprotected, is in
[Security.md](Security.md).

---

## 12. Related

| Document | For |
| --- | --- |
| [Operations.md](Operations.md) | Applying these changes day to day |
| [Deployment.md](Deployment.md) | The deployment surface and startup ordering |
| [Performance.md](Performance.md) | What the load-bearing values produce |
| [InfrastructureDiagram.md](InfrastructureDiagram.md) | The port map, drawn |
| [Security.md](Security.md) | Credentials, exposure and least privilege |
| [Troubleshooting.md](Troubleshooting.md) | When a change did not take effect |
