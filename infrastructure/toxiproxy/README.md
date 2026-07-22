# toxiproxy

The fault injection point for everything below the application: databases, cache, broker, object
store, and the service-to-service hop.

`toxiproxy.json` declares seven TCP proxies and **no toxics**. With no toxics a proxy is a
transparent relay, so the stack behaves normally until something is deliberately broken. Toxics are
added at runtime through the HTTP API on `:8474`, which is what `../../scripts/chaos.sh` drives — no
restart, no rebuild, no configuration reload.

## The proxies

| Proxy | Listens | Forwards to | Used by |
| --- | --- | --- | --- |
| `postgres` | `toxiproxy:15432` | `postgres:5432` | Order Service |
| `oracle` | `toxiproxy:11521` | `oracle:1521` | Inventory Service |
| `redis` | `toxiproxy:16379` | `redis:6379` | both services |
| `kafka` | `toxiproxy:19092` | `kafka:9094` | both services |
| `minio` | `toxiproxy:19000` | `minio:9000` | Order Service |
| `inventory-http` | `toxiproxy:8082` | `inventory-service:8082` | Order Service (Feign), Consul health check |
| `inventory-grpc` | `toxiproxy:9082` | `inventory-service:9082` | Order Service (gRPC channel) |

## Two things worth understanding

**The Kafka proxy needs its own broker listener.** A Kafka client bootstraps against one address and
is then told, by the broker, which address to actually use. Proxying `kafka:9092` would hand the
client the real broker and the proxy would be bypassed on the second connection — silently, so the
toxics would appear to do nothing. The broker therefore publishes a third listener, `PROXY`, on
`:9094`, advertised as `toxiproxy:19092`. A client that arrives through the proxy is told to keep
using it.

**The exporters do not go through the proxy.** `postgres-exporter`, `oracle-exporter`,
`redis-exporter` and `kafka-exporter` address their datastores directly. That is deliberate and it is
the most instructive part of the setup: when a toxic is applied, the exporter keeps reporting a
perfectly healthy database while the service reports timeouts. The disagreement is the diagnosis —
the fault is in the path, not in the datastore. A monitoring stack that shared the application's
network path could not tell you that.

The same reasoning explains why `inventory-http` carries the Consul health check. If the path to a
service is broken then the service is unreachable, whatever the process itself believes, and the
registry should say so.

## Driving it

```bash
../../scripts/chaos.sh list                       # proxies and their active toxics
../../scripts/chaos.sh slow postgres 500          # +500ms on every packet
../../scripts/chaos.sh down inventory-grpc        # refuse connections outright
../../scripts/chaos.sh reset                      # remove every toxic
```

The API is plain HTTP if you would rather use it directly:

```bash
curl -s localhost:8474/proxies | jq
curl -s -X POST localhost:8474/proxies/redis/toxics \
  -d '{"type":"latency","attributes":{"latency":300,"jitter":100}}'
```

Scenarios, with the signal each one should produce, are in
[docs/Simulation.md](../../docs/Simulation.md).
