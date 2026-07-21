# Service Discovery and Configuration — Consul

Step 08 wires both services to Consul through Spring Cloud Consul: they **register** themselves and
their health check on startup, and they **read configuration** from Consul's key-value store,
overriding what ships in their `application.yml`.

The Consul container has existed since step 02; this step is where the services actually talk to it.

---

## 1. Where the pieces live

| Concern | Location |
| --- | --- |
| Consul agent (single-node server) | [`docker-compose.platform.yml`](../docker/compose/docker-compose.platform.yml) `consul`, config [`server.hcl`](../infrastructure/consul/config/server.hcl) |
| KV seed document | [`infrastructure/consul/kv/application.yaml`](../infrastructure/consul/kv/application.yaml) |
| One-shot that seeds KV | `docker-compose.platform.yml` `consul-init` |
| Service registration + config import | each service's `application.yml` → `spring.cloud.consul` and `spring.config.import` |
| Dependencies | `spring-cloud-starter-consul-discovery`, `spring-cloud-starter-consul-config` |

---

## 2. Service registration

Each service registers with Consul on startup and deregisters on shutdown. The agent's address comes
from `CONSUL_HOST`/`CONSUL_PORT` (default `localhost:8500`), which `./scripts/run-service.sh` derives
from `docker/compose/.env` — so a machine that publishes Consul on another port stays consistent
without editing tracked files.

The important subtlety is the **address it advertises**: the services run on the host while Consul
runs in a container, so Consul must be able to reach *back* to health-check them. They register as
`host.docker.internal` (`spring.cloud.consul.discovery.hostname`), the same name the gateway uses for
its upstreams, and the `consul` container is given `extra_hosts: host.docker.internal:host-gateway`
so it resolves. Once the services are themselves containerised, this becomes their compose service
name and nothing else changes.

Registration details:

- **instance id** configured as `${spring.application.name}:${server.port}` and registered as
  `order-service-8081` / `inventory-service-8082`: Spring Cloud normalises the id for DNS, so the
  colon becomes a hyphen. Stable and human-readable either way.
- **tag** `observability-lab`.
- **`fail-fast: false`** — a registry that is briefly down does not stop a service from starting; it
  registers when Consul returns.

## 3. Health checks

Consul is told to poll `http://host.docker.internal:<port>/actuator/health` every 10s
(`health-check-path`, `health-check-interval`). That endpoint is deliberately open (the resource
server permits `/actuator/**`), so the check needs no token. A service that stops answering `UP` is
marked failing in the catalog within a couple of intervals.

The service's *own* health does **not** include a Consul indicator
(`management.health.consul.enabled: false`). If it did, the endpoint Consul checks would itself
depend on Consul, coupling each service to the very thing checking it.

## 4. Configuration from Consul KV

`spring.config.import: "optional:consul:"` makes each service read a configuration document from KV
*before* its context starts. Because Consul config is external configuration, its values take
**precedence** over the bundled `application.yml`.

- **Key:** `config/application/data` — the shared context every service reads. A
  `config/<service-name>/data` document would override just that one service.
- **Format:** a single YAML document per key (`format: yaml`, `data-key: data`), so an override reads
  like the file it overrides rather than as one flat key per property.
- **`optional:` + `fail-fast: false`:** when Consul is absent (tests, a standalone run) the service
  simply uses its bundled defaults.
- **Watch:** enabled, so a KV change is picked up within seconds without a restart (properties bound
  through `@ConfigurationProperties`/`@RefreshScope` refresh; a plain `@Value` needs a refresh event).

### The visible proof

Both services expose `info.platform.config-source` at `/actuator/info`:

- bundled default → `"application.yml default (Consul KV not applied)"`
- when KV is seeded → `"Consul KV (config/application/data)"`

Seeing the second value confirms the service read its configuration from Consul rather than only from
its jar.

---

## 5. Verify it

Bring up the stack (or at least `consul` + `consul-init`) and run a service, then:

```bash
# Registered instances and their health, straight from the catalog
curl -s http://localhost:8500/v1/health/service/order-service \
  | python3 -c 'import json,sys; [print(s["Service"]["Address"], s["Service"]["Port"], [c["Status"] for c in s["Checks"]]) for s in json.load(sys.stdin)]'

# The seeded KV document
curl -s http://localhost:8500/v1/kv/config/application/data?raw

# The override in effect
curl -s http://localhost:8081/actuator/info | python3 -m json.tool
```

The Consul UI at <http://localhost:8500> shows the same: **Services** for the catalog and health,
**Key/Value** for `config/application/data`.

---

## 6. Operations

- **Re-seed / change config:** edit `infrastructure/consul/kv/application.yaml` and re-run the
  one-shot — `docker compose ... up -d --force-recreate consul-init` — or `consul kv put
  config/application/data @-` directly. Watching services pick it up within seconds.
- **KV survives restarts:** it lives in the `lab-consul-data` volume; `./scripts/infra.sh destroy`
  removes it and the next `up` re-seeds.
- **ACLs are off** (`default_policy = allow`) and script checks are disabled — a single-node
  loopback-bound lab, kept deliberately simple to teach the mechanics.

## 7. Discovery in use

Step 08 made the services *register*. Step 09 made something *look them up*: the Order Service's
Feign client declares `@FeignClient(name = "inventory-service")` with no URL, and Spring Cloud
LoadBalancer resolves it through this registry. `POST /api/v1/orders/availability` is the path that
exercises it — see [Kafka.md](Kafka.md) for how the asynchronous half of the same integration works.

Two things about registration turned out to matter only once something resolved it:

- **The advertised address must work from both sides.** Consul health-checks the service from inside
  its container, and other services reach it from wherever they run. `host.docker.internal` satisfies
  only the first — it does not resolve on the host — so discovery handed callers an address they
  could not connect to. `./scripts/run-service.sh` therefore sets `SERVICE_HOSTNAME` to the machine's
  own LAN address, which satisfies both, and falls back to `host.docker.internal` when it cannot
  determine one. Once the services are containerised this becomes their compose service name.
- **`query-passing: true`.** Without it the catalog returns every registered instance including the
  ones Consul already knows are failing, which makes the health check decorative. With it, an
  instance is routed to only while its check passes — and a service that has just started is
  correctly invisible until its first check succeeds.
