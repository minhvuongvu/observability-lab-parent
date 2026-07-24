# Failure Simulation — the in-process faults

Thirteen production failures, each reproducible by one command, each with its expected signals written
down **before** the run.

These are the faults a process must inflict on **itself**. Faults in the network path — a slow
database, an unreachable broker, a black-holed object store — are injected from outside by Toxiproxy
and live in [Simulation.md](Simulation.md). The split is not arbitrary: a fault injected from outside
needs no application code and cannot be left switched on in production, so anything that can live out
there does. A memory leak, a CPU spike and a deadlock cannot.

```bash
./scripts/scenario.sh list                    # every scenario, one line each
./scripts/scenario.sh memory-leak             # inject, hold, heal, report
./scripts/scenario.sh db-exhaustion --under-load
./scripts/chaos.sh reset                      # clears BOTH levels, always
```

---

## How to read a scenario

Four parts, in this order, and the order matters:

1. **Inject** — the exact command
2. **Expected** — what every signal should show, per signal
3. **Verify** — the concrete query
4. **What it teaches** — the failure this makes legible

**Read Expected before running Inject.** A scenario whose expected signals were decided after seeing
the output confirms nothing. Where the observed behaviour differs, the finding is the gap — and two
of the entries below exist only because that happened.

---

## The guards, and why there are three

| Guard | Mechanism | What it stops |
| --- | --- | --- |
| Profile | `@Profile({"local","dev"})` | Under `prod` the beans do not exist, so the paths return **404** — not 403, which would confirm they are there |
| Property | `app.chaos.enabled` | A shared dev environment can switch the feature off without a rebuild |
| Role | `ADMIN` on `/api/v1/chaos/**` | Every method, not just DELETE — a POST here can deadlock the process |

Verified, not assumed:

```
no token        401
alice  (USER)   403
manager(ADMIN)  200
through the edge 404   ← Kong routes only /api/v1/orders and /api/v1/stock
```

Every toggle also expires on its own (`app.chaos.default-ttl`, 5 minutes). "Forgot to reset" is a lab
that recovers by itself rather than a debugging session three days later chasing a fault somebody
injected on purpose.

**`lab_chaos_active_faults`** is a gauge on every service. Non-zero means a symptom is deliberate. Overlay it
on any panel to answer "was that us?" — an injected fault and a real one are otherwise identical in
every other signal.

---

## 1. Slow request

### Inject
```bash
./scripts/chaos.sh app latency order 800
```

### Expected
| Signal | Expectation |
| --- | --- |
| **HTTP** | p95 above 800 ms within one scrape interval. Error rate stays at 0 |
| **Tomcat** | `tomcat_threads_busy` climbs — the thread is *held*, not parked |
| **Health** | `/actuator/health/readiness` stays **UP** and fast. The filter exempts it |
| **Chaos API** | also fast. The switch must never sit behind the fault |
| **Gateway** | Kong's 15 s `read_timeout` is not reached at 800 ms; raise it past 15 s to see a 504 |
| **Traces** | the server span carries the added time; no child span accounts for it |
| **Alerts** | `HighLatency` after its `for:` window |

### Verify
```promql
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{service="order-service"}[1m])))
tomcat_threads_busy_threads{service="order-service"}
```

### What it teaches
A slow service is not a slow endpoint. The fault is applied to *every* API call, so the dashboards,
the alerts, the gateway timeouts and the callers' breakers all see what a real degradation looks like.

The exemptions are the interesting part. Health stays green while the service is unusable — which is
precisely why latency alerts exist when health checks are green, and why a load balancer that only
reads health keeps sending traffic to a service that cannot serve it.

---

## 2. Database timeout / pool exhaustion

### Inject
```bash
./scripts/chaos.sh app db order 120 12      # 12 connections held; the pool has 10
```

### Expected
| Signal | Expectation |
| --- | --- |
| **Hikari** | `hikaricp_connections_active` saturates, then `hikaricp_connections_pending` climbs — the leading indicator |
| **HTTP** | 500s appear ~3 s later, when `connection-timeout: 3000` starts firing |
| **Which endpoints** | **all of them**, including ones with nothing to do with the database |
| **postgres-exporter** | perfectly healthy. No slow queries, normal connection count |
| **Logs** | `Connection is not available, request timed out after 3000ms (total=10, active=10, waiting=N)` |
| **Traces** | the time is in connection acquisition, before any SQL span |

### Verify
```promql
hikaricp_connections_pending{service="order-service"}
rate(http_server_requests_seconds_count{service="order-service",outcome="SERVER_ERROR"}[1m])
```

### What it teaches
The endpoint that breaks is rarely the endpoint at fault. Pool exhaustion turns one slow query into a
whole service that stops answering, and `pending` is the earliest honest warning — by the time the
error rate moves, the incident is minutes old.

Note this uses `pg_sleep`, so the connection is genuinely occupied *at the database*. A Java thread
holding a pool object would exhaust the pool with no server-side evidence at all, and half the lesson
is knowing which side of the connection the evidence appears on.

---

## 3. Redis failure — the quiet one

### Inject
```bash
./scripts/chaos.sh app cache order miss     # or: fail
```

### Expected
| Signal | Expectation |
| --- | --- |
| **HTTP** | latency rises. Errors stay at **0** in `miss` mode |
| **Redis** | `up{job="redis"} == 1`. The connection is fine |
| **Health** | readiness stays **UP**. Nothing takes the instance out of rotation |
| **Database** | query rate jumps by the former cache hit rate |
| **Alerts** | **none fire.** That is the finding, not a gap in the scenario |

### Verify
```promql
up{job="redis"}
rate(hikaricp_connections_acquire_seconds_count{service="order-service"}[1m])
```

### What it teaches
Compare with `./scripts/chaos.sh down redis`, which severs the connection: Lettuce notices, readiness
goes DOWN, Kong removes the instance, and `RedisDown` fires. Loud, and easy.

This is the version no health check can see. Every signal the platform has says the service is
healthy while its origin quietly takes the full uncached load. Cache stampedes look like this, and so
does a misconfigured serializer.

`miss` is the more instructive mode; `fail` tests the caller's error handling instead.

---

## 4. CPU spike

### Inject
```bash
./scripts/chaos.sh app cpu order 4 60
```

### Expected
| Signal | Expectation |
| --- | --- |
| **CPU** | `process_cpu_usage` climbs toward the container's `cpus` limit |
| **Heap** | **flat.** This is the pairing that identifies it |
| **Latency** | rises across the board once the burn threads compete with request threads |
| **Profiles** | the burn loop dominates the flame graph immediately |
| **Alerts** | `HighCpu` after its window |

### Verify
```promql
process_cpu_usage{service="order-service"}
jvm_memory_used_bytes{service="order-service",area="heap"}
```

### What it teaches
CPU up + heap flat is a computation problem. Scenario 5 is the mirror image — heap up, CPU flat until
the collector starts fighting for it — and telling the two apart from the metrics alone is the skill.

The burn loop's result is deliberately consumed. Discard it and the JIT deletes the loop as dead
code, producing a "CPU spike" that spikes nothing.

---

## 5. Memory leak

### Inject
```bash
./scripts/chaos.sh app memory order 256
./scripts/chaos.sh app memory order 256      # again, to watch it accumulate
```

### Expected
| Signal | Expectation |
| --- | --- |
| **Heap** | the sawtooth's **floor rises after each full GC** and never comes back down |
| **GC** | pause time and frequency both climb as the collector works harder for less |
| **Container memory** | rises with the heap, because the pages are touched |
| **CPU** | climbs indirectly, from GC |
| **Profiles** | `alloc_live` names the allocation site. `alloc` alone would not — most allocation is short-lived garbage |
| **End state** | at the 512 MB ceiling against a 768 MB container: GC thrash, then an OOM kill |

### Verify
```promql
jvm_memory_used_bytes{service="order-service",area="heap"}
rate(jvm_gc_pause_seconds_sum{service="order-service"}[1m])
```

### What it teaches
**A rising floor is the leak.** Peak heap tells you nothing — a healthy service touches its ceiling
constantly. It is the level *after* a full collection that distinguishes accumulation from load.

`alloc_live` versus `alloc` is the other half. "What allocated the most" and "what is still holding
memory" are different questions, and only the second finds a leak.

Release with `./scripts/chaos.sh app release-memory order`. The heap stays high until the next
collection — "released" and "reclaimed" are different events, and conflating them misreads the graph.

---

## 6. Deadlock

### Inject
```bash
./scripts/chaos.sh app deadlock order
```

**This cannot be undone.** Two threads are lost until the container restarts. That is not a limitation
to work around — it is the fault being modelled, and "restart the process" is the real remedy.

### Expected
| Signal | Expectation |
| --- | --- |
| **Metrics** | **nothing.** No metric says "deadlock" |
| **Logs** | nothing. The two threads simply never log again |
| **Traces** | nothing. No span is ever completed, so no span is ever exported |
| **Health** | **UP.** Two threads out of two hundred is not noticed |
| **Thread count** | `jvm_threads_states_threads{state="blocked"}` +2 — the only metric that moves at all |
| **The JVM** | knows exactly, and will say so the moment anything asks |

### Verify
```bash
docker exec lab-order-service jcmd 1 Thread.print | grep -A20 -i deadlock
curl -s -H "Authorization: Bearer $TOKEN" localhost:8081/api/v1/chaos | grep deadlockedThreads
```

### What it teaches
The most important scenario here, because it is the one where **the observability stack fails**. Four
signals, and not one of them volunteers the answer. `ThreadMXBean.findDeadlockedThreads()` returns it
instantly — and nothing scrapes that.

Scale it up mentally: at two threads it is invisible; at two hundred it is an outage with no
diagnosis. The lesson is that a thread dump belongs in the runbook, because no dashboard will get you
there.

---

## 7. Exception simulation

### Inject
```bash
./scripts/chaos.sh app exception order 0.25
```

### Expected
| Signal | Expectation |
| --- | --- |
| **HTTP** | ~25% of calls return **500**, in the platform's normal envelope |
| **Body** | `{"success":false,"error":{"code":"PLT-5000",...},"meta":{"traceId":...}}` — identical in shape to a real failure |
| **Logs** | `ChaosInjectedException` at ERROR, with the trace id in the MDC |
| **Traces** | the server span is `ERROR` with the exception recorded |
| **Alerts** | `HighErrorRate` after its `for:` window |

### Verify
```promql
sum(rate(http_server_requests_seconds_count{service="order-service",outcome="SERVER_ERROR"}[1m]))
  / sum(rate(http_server_requests_seconds_count{service="order-service"}[1m]))
```
```logql
{service="order-service"} |= "ChaosInjectedException"
```

### What it teaches
The `ratio` is the point. At 1.0 the failure is obvious; at 0.25 the mean latency stays healthy and
only the error rate moves, which is what a real partial failure does and what dashboards are worst at.

**This scenario found a bug in its own first implementation.** The fault was thrown from a servlet
filter, which unwinds *past* `@RestControllerAdvice` — so the response was the container's default
error page, with no envelope and no trace id. An injected failure that looks different on the wire
from a real one is simulating the wrong thing. It now throws from a `HandlerInterceptor`, inside the
dispatcher, and the response is byte-identical in shape to a genuine error.

---

## 8. Dead letter queue

### Inject
```bash
./scripts/chaos.sh app dlq order
```

### Expected
| Signal | Expectation |
| --- | --- |
| **Consumer** | the listener throws `ValidationException` |
| **Retry** | `ValidationException` is in `addNotRetryableExceptions`, so it goes **straight** to the DLQ without retrying — a rule that refused once refuses identically every time |
| **Dead-letter topic** | exactly **one** new record, with headers naming the source topic, partition, offset and exception |
| **Lag** | returns to **0**. The partition is not blocked |
| **Order flow** | unaffected. Other partitions never notice |
| **Nothing** | consumes the DLQ automatically |

### Verify
```bash
docker exec lab-kafka /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server localhost:9092 --topic dead-letter-topic
docker exec lab-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group order-inventory-updated-group
```

### What it teaches
> **This scenario found a real defect on its first run, and it was serious.**
>
> `DeadLetterPublishingRecoverer` was wired to the application's `KafkaTemplate<String, String>`. But
> the listener deserializes records into `InventoryUpdatedMessage` objects, so the recoverer handed
> `StringSerializer` an object and got `ClassCastException`. The dead-letter publication failed, the
> record was never recovered, and the error handler **retried it forever** — one poison message
> permanently blocking its partition, with lag stuck at 1 and no dead-letter record to explain why.
>
> Two partitions of `inventory-updated` were blocked before it was noticed. The fix gives the
> recoverer its own `JsonSerializer`-backed template; the stuck records drained immediately and lag
> returned to 0.
>
> The code had been reviewed, documented and shipped. **A dead-letter path that has never been
> exercised is a dead-letter path that does not work** — everything about it looks correct until a
> real poison message arrives, and by then it is an outage rather than a finding.

A second, smaller lesson from the same run: the first poison message was *well-formed but referred to
a nonexistent order*, and the service handled it perfectly — settling a missing order is idempotent by
design. Nothing threw, nothing retried, nothing was dead-lettered, and the scenario reported success
while proving nothing. **A poison message must violate the consumer's actual contract**, not merely
look wrong. `requireUsable` is where that contract is written down.

---

## 9. Kafka producer failure

### Inject
```bash
./scripts/chaos.sh app kafka order
```

### Expected
| Signal | Expectation |
| --- | --- |
| **Producer** | `RecordTooLargeException` — 4 MB against a 1 MB `max.request.size` |
| **When** | immediately, **inside the client**. No byte reaches the broker |
| **Broker** | completely healthy. No reconnection, no network retry |
| **Logs** | one ERROR on the producing service |

### Verify
```logql
{service="order-service"} |= "RecordTooLarge"
```

### What it teaches
Distinct from `./scripts/chaos.sh down kafka`, which severs the connection and produces reconnection
attempts, retries and eventually a delivery timeout. This one fails instantly with a healthy cluster —
the shape of the failure that appears when a payload grows past a limit nobody remembered was there.

---

## 10. Bulk logging

### Inject
```bash
./scripts/chaos.sh app logs order 50000 INFO
```

### Expected
| Signal | Expectation |
| --- | --- |
| **Loki** | the count for the burst id should equal the number emitted |
| **Reality** | it frequently does not — the async appender's ring buffer drops under pressure |
| **Which component** | find out: the appender, the agent, or Loki's ingestion limits |
| **Whether anything says so** | usually not, which is the point |
| **Disk** | the JSON file grows; the volume is shared with the shipping agents |

### Verify
```logql
count_over_time({service="order-service"} |= "<burstId from the response>" [10m])
```

### What it teaches
Every line carries a distinct sequence number, so gaps are **countable rather than suspected**. A log
pipeline that silently drops under load is one you cannot trust during the incident where you need it
most — and the only way to know your drop threshold is to cross it deliberately.

---

## 11. Large payload

### Inject
```bash
./scripts/chaos.sh app payload order 8192
```

### Expected
| Signal | Expectation |
| --- | --- |
| **Direct to the service** | succeeds; 8 MB transferred |
| **Through the edge** | **404** — Kong routes only `/api/v1/orders` and `/api/v1/stock` |
| **Compression** | the payload repeats, so bytes on the wire are far below the response size |
| **Heap** | a spike per concurrent request; the response is assembled in memory |

### What it teaches
The edge 404 is itself worth having seen: the chaos endpoints are not reachable through the gateway at
all, which is a fourth guard nobody configured deliberately. To exercise Kong's
`request-size-limiting` plugin, point the payload at a routed path instead.

---

## 12. High traffic, from inside

### Inject
```bash
./scripts/chaos.sh app traffic order 2000 100
```

### Expected
| Signal | Expectation |
| --- | --- |
| **Hikari** | `pending` climbs; the pool saturates |
| **Edge** | **no additional traffic at all.** Kong and Nginx see nothing |
| **Rate limiter** | never engages |
| **Tomcat** | request threads stay comparatively idle — the work is on chaos threads |

### What it teaches
Complements a k6 run rather than duplicating it. k6 offers load at the front door, where the rate
limiter and accept queue get a say — [Simulation.md §2](Simulation.md#2-high-load) shows the lab
saturating at ~10 orders/s there. This submits work straight onto threads inside the JVM, so the
bottleneck it finds is the application's rather than the edge's.

---

## 13. Retry and circuit breaker

### Inject
```bash
./scripts/chaos.sh down inventory-grpc        # break it for real, first
./scripts/chaos.sh app breaker order 40
```

### Expected
| Signal | Expectation |
| --- | --- |
| **Breaker** | `CLOSED` → `OPEN` once the failure rate crosses its threshold |
| **Reported failure rate** | ~85% at 40 calls |
| **Caller's view** | **all 40 "succeed"** — and that is not a contradiction |
| **Retry** | attempts per the policy, then gives up within the deadline |
| **Orders** | still accepted, as `PENDING`, settled later over Kafka |
| **Alerts** | `GrpcChannelNotReady` after 2 min |

### Verify
```promql
resilience4j_circuitbreaker_state{name="inventory-grpc"}
resilience4j_circuitbreaker_calls_total{name="inventory-grpc"}
```

### What it teaches
The two numbers count different things, and the gap between them is the whole design. `succeeded`
counts what the **caller** saw; the breaker counts what the **gRPC channel** saw. High success with a
tripped breaker is the fallback absorbing an outage — exactly what it is for.

The endpoint deliberately does not force the breaker OPEN. Setting a state field proves a field can be
assigned; issuing real calls through a genuinely broken dependency proves the sliding window, the
failure-rate threshold and the slow-call detector are configured such that a real outage trips them.
That is the only claim worth testing.

---

## 14. Vault sealed — the one with no symptom

The most instructive fault in this file, and the only one where the correct first reaction is to
*not* go looking for a symptom.

### Inject
```bash
./scripts/chaos.sh vault seal
```

### Expected
| Signal | Expectation |
| --- | --- |
| **HTTP** | **No change.** Latency flat, error rate 0. Orders keep being created |
| **Health** | Both services stay `healthy`. `infra.sh health` is entirely green |
| **Database** | The Order Service keeps using its existing dynamic credential and its open pool |
| **Vault metrics** | `max(vault_core_unsealed)` drops to 0 **immediately** — the only signal that moves |
| **Container health** | `lab-vault` goes `unhealthy` within ~15 s; its healthcheck is `vault status` |
| **Alerts** | `VaultSealed`, critical, after 1m. Nothing else fires |
| **Restart a service** | *This* is where it breaks: `VaultLoginException: Cannot login using AppRole: Vault is sealed`, and the container enters a restart loop |

### Verify
```promql
max(vault_core_unsealed)
up{job="vault"}
```
```bash
./scripts/vault.sh status                    # Sealed  true
curl -s -X POST http://localhost/api/v1/orders ...   # still 201
docker restart lab-order-service             # now it cannot start
```

### What it teaches
**The distance between cause and symptom.** Every other fault in this document announces itself within
a scrape interval. This one announces itself at the next lease renewal or the next restart — minutes
or an hour later, by which point the change that caused it is off the left edge of the dashboard and
the failure looks like a database problem.

That is why `VaultSealed` alerts on seal state directly rather than on its consequences, and why
`./scripts/vault.sh status` is one of the six checks at the top of [Runbook.md](Runbook.md). By the
time the consequences are measurable, somebody is debugging the wrong subsystem.

It is also why `./scripts/chaos.sh reset` unseals: a fault with no symptom is the one most likely to
be left injected and forgotten.

### Heal
```bash
./scripts/chaos.sh vault unseal     # or: ./scripts/chaos.sh reset
```

---

## 15. Dynamic credential revoked

### Inject
```bash
./scripts/vault.sh revoke
```

Vault drops the PostgreSQL users it created. They stop existing — this is not a token being
invalidated somewhere, it is `DROP ROLE`.

### Expected
| Signal | Expectation |
| --- | --- |
| **Immediately** | Nothing. Established connections were authenticated once and are not re-checked |
| **Vault** | `vault_expire_num_leases` drops to 0; `VaultLeaseCountCollapsed` after 5m |
| **Within `max-lifetime`** (15m) | Hikari recycles a connection, tries to open a new one, and fails |
| **HTTP** | 5xx on any request needing a fresh connection; `DatabasePoolExhausted` follows |
| **Forced immediately** | `./scripts/chaos.sh app db order 30 20` demands more connections than the pool holds, so the failure surfaces at once |

### Verify
```bash
./scripts/vault.sh leases     # none
./scripts/vault.sh whoami     # the v-approle-… user is gone from pg_stat_activity
```

### What it teaches
Why `spring.datasource.hikari.max-lifetime` must stay **below** the Vault lease TTL. The pool is the
component that discovers a dead credential, and it discovers it on its own schedule — so the recycling
interval decides whether the failure appears in a controlled trickle the pool absorbs, or as a cliff
when every connection expires at once.

It also shows why the *lease*, not the token, is the unit that matters here: the service's Vault token
is still perfectly valid throughout. It simply has nothing left to authenticate to PostgreSQL with.

### Heal
```bash
docker restart lab-order-service     # re-leases at startup
./scripts/vault.sh whoami            # a new v-approle-… user
```

---

## Combining with load

A fault on an idle system tells you what the mechanism does. A fault under load tells you what it does
when it matters, and the answers diverge sharply:

```bash
./scripts/scenario.sh db-exhaustion --under-load
```

The runner starts k6, lets the ramp establish a baseline, then injects. See
[Simulation.md scenario 7](Simulation.md#scenario-7--load-and-fault-together) for the worked
comparison — 200 ms of added latency is nothing on an idle system and enough to collapse a saturated
one, because the pool is already fully used and latency per checkout multiplies the queue rather than
adding to it.

---

## Rules

**Write the expectation down first.** Two of the entries above exist only because the observed
behaviour differed from the prediction, and both were defects in the system rather than the scenario.

**One fault at a time.** Two simultaneous faults produce an interaction, and attribution becomes
guesswork.

**Reset between runs.** `./scripts/chaos.sh reset` clears both levels — network toxics and in-process
toggles, on both services. `scenario.sh` does it on entry and on exit, including on Ctrl-C.

**A quiet alert is not a passing alert.** If a scenario should have fired something and did not, the
finding is the alert.

**If the edge starts answering 503 after a session, reload the gateway.** Kong's passive health checks
mark a target unhealthy after five 5xx responses, which a `ratio: 1.0` exception run produces in
seconds — and if the container is recreated afterwards, the balancer keeps the old address. The
service answers perfectly on its own port while the edge refuses everything:

```bash
curl -s localhost:8001/upstreams/order-service.upstream/health   # target: UNHEALTHY
./scripts/gateway.sh reload                                      # target: HEALTHY
```

This is not a defect. It is the gateway doing exactly what it should — refusing to send traffic to
something that returned five server errors — and it is worth having seen once, because the symptom
(edge 503, service 200) reads like a routing bug and is a health-check state.

**`deadlock` is permanent.** `scenario.sh all` deliberately skips it, because it would poison every
scenario after it.

---

## Related

| Document | What it covers |
| --- | --- |
| [Simulation.md](Simulation.md) | Network faults and load generation — the other half |
| [Alerting.md](Alerting.md) | Which alerts these scenarios should fire, and their thresholds |
| [Observability.md](Observability.md) | Reading the four signals together |
| [GRPC_FAILURE_SIMULATION.md](../GRPC_FAILURE_SIMULATION.md) | gRPC-specific scenarios: deadlines, status codes, streaming |
