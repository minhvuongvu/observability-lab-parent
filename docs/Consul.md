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

Each service registers with Consul on startup and deregisters on shutdown. The agent's address is
`consul:8500` — a name on `lab-net`, since every component of the lab is a container on it.

The important subtlety is the **address it advertises**
(`spring.cloud.consul.discovery.hostname`, set from `SERVICE_HOSTNAME`), because Consul must be able
to reach *back* to health-check it and other services connect to whatever the registry hands them.
The two services answer that question differently, and the difference is deliberate:

| Service | Advertises | Why |
| --- | --- | --- |
| `order-service` | `order-service` | Its own compose name. Nothing needs to intercept traffic to it |
| `inventory-service` | `toxiproxy` | So the service-to-service hop runs through the fault proxy and can be made slow or broken at runtime |

The second is not a hack — it is exactly what a service mesh sidecar does: an instance behind Envoy
advertises the sidecar's address, not its own. It also means Consul's health check runs through the
proxy, which is correct: if the path to a service is broken then the service is unreachable, whatever
the process itself believes.

Set `INVENTORY_ADVERTISE_HOST=inventory-service` in `docker/compose/.env` for the direct path.

This used to be `host.docker.internal`, because the services were processes on the developer's
machine — see §7 for why that address could never quite work.

Registration details:

- **instance id** configured as `${spring.application.name}:${server.port}` and registered as
  `order-service-8081` / `inventory-service-8082`: Spring Cloud normalises the id for DNS, so the
  colon becomes a hyphen. Stable and human-readable either way.
- **tag** `observability-lab`.
- **`fail-fast: false`** — a registry that is briefly down does not stop a service from starting; it
  registers when Consul returns.

## 3. Health checks

Consul is told to poll `http://<advertised-host>:<port>/actuator/health` every 10s
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

- **The advertised address must work from both sides.** Consul health-checks the service, and other
  services connect to whatever the catalog hands them; both have to be able to reach it. While the
  services ran on the host this was genuinely awkward — `host.docker.internal` resolves from inside a
  container but not on the host, so discovery handed callers an address they could not connect to,
  and `run-service.sh` had to substitute the machine's own LAN address to satisfy both.
  Containerising the services deleted the problem: a compose name resolves identically from every
  container on `lab-net`, which is every component there is.
- **`query-passing: true`.** Without it the catalog returns every registered instance including the
  ones Consul already knows are failing, which makes the health check decorative. With it, an
  instance is routed to only while its check passes — and a service that has just started is
  correctly invisible until its first check succeeds.
