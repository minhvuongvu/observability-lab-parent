# k6

The load generator. It runs **inside `lab-net`** as a container, not on the host — so it reaches the
services over the same Docker network they use to reach each other, rather than through the host's
loopback stack, which has different latency characteristics and no notion of the gateway.

```bash
../../scripts/load.sh smoke     # 1 VU, 1 minute — is the system wired up?
../../scripts/load.sh load      # 10 orders/s for 5 minutes
../../scripts/load.sh stress    # climb to 100/s until something gives
../../scripts/load.sh spike     # idle, then 80/s in ten seconds, then idle
../../scripts/load.sh soak      # 5/s for two hours — finds what accumulates
```

## The scenarios

| Scenario | Shape | The question it answers |
| --- | --- | --- |
| `smoke.js` | 1 VU, 1 min | Is every component on the path actually working? |
| `load.js` | ramp to a held arrival rate | How does the system behave at the expected traffic? |
| `stress.js` | climb past the limit | Which resource runs out first, and is the failure graceful? |
| `spike.js` | idle → wall → idle | What do the cold paths do, and how long is recovery? |
| `soak.js` | low rate, hours | What accumulates — leaks, lag, unreturned connections? |

Every scenario takes env overrides: `RATE`, `DURATION`, `PEAK`, `SKU_COUNT`, `BASE_URL`.

**The defaults are measured, not chosen.** This lab saturates at around 10 orders/s — 10/s runs clean
at p95 ~49 ms, 20/s returns 28% errors, all of them connection-pool timeouts. That ceiling is
deliberate: the Order Service has a 10-connection pool and a 1.5-CPU limit so that queueing and pool
exhaustion are reachable in ninety seconds on a laptop. Raise either and re-measure before raising
`RATE`. See [docs/Simulation.md](../../docs/Simulation.md#2-high-load).

## Arrival rate, not virtual users

All five use one of k6's `arrival-rate` executors. The distinction matters more than it looks: with a
fixed number of virtual users, each waits for its response before sending the next request — so a
system that slows down *receives less traffic*, and hides its own degradation behind a throughput
number that stays flat.

A fixed arrival rate offers the same load regardless of how the system copes. Saturation then shows up
where it should: rising latency, a rising VU count, and eventually `dropped_iterations`.

## Results land in Grafana

`scripts/load.sh` runs k6 with `--out experimental-prometheus-rw`, so every k6 metric is remote-written
into the same Prometheus the platform scrapes — which is why `prometheus.yml` runs with
`--web.enable-remote-write-receiver`.

That means the load and its effect are on one time axis: `k6_http_req_duration` beside
`jvm_gc_pause_seconds` beside `hikaricp_connections_pending`, at the same instant. A load test whose
results live in a separate tool leaves you eyeballing two clocks.

## Through the edge

The default `BASE_URL` is `http://order-service:8081` — straight at the service. Kong rate-limits each
route to 120 requests a minute, so a run aimed at the edge measures the rate limiter and little else.

That is a worthwhile experiment on its own, and it is what the override is for:

```bash
BASE_URL=http://nginx ../../scripts/load.sh load
```

Expect 429s almost immediately, `kong_http_requests_total{code="429"}` climbing, and the service
itself perfectly idle behind it. Seeing the edge absorb a flood that never reaches the service is the
point of having an edge.

## Combining with faults

The two halves of the simulation stack are designed to be used together — a fault under load behaves
nothing like a fault on an idle system:

```bash
../../scripts/load.sh load &          # sustained traffic
sleep 60
../../scripts/chaos.sh slow postgres 400
```

Scenarios written out signal by signal are in [docs/Simulation.md](../../docs/Simulation.md).
