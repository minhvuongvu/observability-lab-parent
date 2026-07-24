# Alerting

Thirty-three Prometheus rules, two Grafana rules, three severities, two delivery channels — and one
rule about all of them:

> **Alert on what a person would act on. Everything else is a dashboard.**

Every other decision in this document follows from that. An alert channel people mute is worse than
no alerting at all, because it is a channel everyone *believes* is watching.

---

## 1. What changed at step 16

Before this step, rules evaluated and **nothing was notified**. Prometheus showed them firing, and
that was the end of the chain. Step 16 adds the parts that make an alert reach somebody:

| Added | Why it was needed |
| --- | --- |
| **Alertmanager** | Prometheus has no concept of severity routing, grouping, inhibition or silences. All four live here. |
| **Severity + category labels** on every rule | The routing tree matches on them. A rule without them reaches the fallback receiver. |
| **`impact` / `action` / `runbook` annotations** | An alert that does not say what to do next gets escalated blindly, which costs somebody else's time to answer a question the alert already knew. |
| **Mailpit** and a **webhook sink** | So a notification can be *seen* arriving rather than assumed. |
| **Five exporters** | Four categories of required alert — host, both databases, Redis, Kafka — had no series to fire on. The services export only their own client-side view of each. |
| **Grafana alert rules** | For Loki queries, which Prometheus cannot reach. |

---

## 2. Severity — the only label that changes who is woken

| Severity | Means | Delivery | Repeat | Fires this often, ideally |
| --- | --- | --- | --- | --- |
| **critical** | A user is affected, or is about to be. Somebody acts now. | Email → `oncall@` **and** webhook | 1h | Rarely. If nobody acts, the severity is wrong |
| **warning** | Worth a ticket today. Not a phone call tonight. | Email → `team@` | 6h | A few a week |
| **info** | Context, not a call to action. | Email → `digest@`, batched | 24h | Freely — nobody is paged |

**The test for critical** is not "how bad does this sound". It is: *would you want to be woken at
03:00 for this?* `HighCpuUsage` sounds alarming and is a warning, because a busy machine with healthy
latency is a machine doing its job. `OrdersAcceptedButNotSettled` sounds mild and is critical, because
customers are being told their orders are pending and they stay pending.

### Categories

`infrastructure` · `application` · `database` · `messaging` · `cache`

These drive grouping and inhibition rather than urgency. They are what lets "the database is down"
suppress the twenty application warnings it causes.

---

## 3. The alert matrix

38 Prometheus rules. Generated from the rule files under
[`infrastructure/prometheus/rules/`](../infrastructure/prometheus/rules/) — if the two ever disagree,
the rule files are right.

### Critical — somebody acts now

| Alert | Category | For | Fires when | First action |
| --- | --- | --- | --- | --- |
| [`ServiceDown`](#servicedown) | infrastructure | 1m | `up == 0` for a service | Is the process alive? Then its logs |
| [`DiskSpaceCritical`](#diskspacecritical) | infrastructure | 5m | Filesystem >90% | Free space now — `docker system prune` |
| [`PostgresDown`](#postgresdown) | database | 1m | `pg_up == 0` | `docker compose ps postgres`, then disk |
| [`OracleDown`](#oracledown) | database | 1m | `oracledb_up == 0` | Confirm it is not still starting |
| [`KafkaBrokerDown`](#kafkabrokerdown) | messaging | 1m | `kafka_brokers < 1` | Restart; the outbox drains itself |
| [`HighHttpFaultRate`](#highhttpfaultrate) | application | 5m | >5% of requests are 5xx | HTTP dashboard → endpoint → trace |
| [`GrpcHighFaultRate`](#grpchighfaultrate) | application | 5m | >1% of RPCs fault | gRPC status distribution |
| [`OrdersAcceptedButNotSettled`](#ordersacceptedbutnotsettled) | application | 10m | Accept rate exceeds settle rate | Consumer lag, then outbox |
| [`VaultSealed`](#vaultsealed) | infrastructure | 1m | `max(vault_core_unsealed) == 0` | `./scripts/vault.sh unseal` |
| [`VaultDown`](#vaultsealed) | infrastructure | 1m | The Vault scrape target is down | `./scripts/infra.sh logs vault` |
| [`VaultAuditDeviceFailing`](#vaultauditdevicefailing) | infrastructure | 2m | Audit log writes are failing | Check disk — Vault refuses **everything** when audit fails |

### Warning — a ticket today

| Alert | Category | For | Fires when |
| --- | --- | --- | --- |
| [`ExporterDown`](#exporterdown) | infrastructure | 2m | An exporter stopped being scraped |
| [`HighCpuUsage`](#highcpuusage) | infrastructure | 10m | Host CPU >85% |
| [`HighMemoryUsage`](#highmemoryusage) | infrastructure | 10m | Host memory >90% |
| [`DiskSpaceFilling`](#diskspacefilling) | infrastructure | 15m | Filesystem >80% |
| [`HighHttpLatency`](#highhttplatency) | application | 10m | p99 >1s |
| [`TomcatThreadPoolSaturated`](#tomcatthreadpoolsaturated) | application | 5m | >85% of request threads busy |
| [`JvmHeapPressure`](#jvmheappressure) | application | 10m | Heap >90% of max |
| [`JvmGcPauseTimeHigh`](#jvmgcpausetimehigh) | application | 10m | >10% of wall clock in GC |
| [`OutboxBacklogGrowing`](#outboxbackloggrowing) | application | 3m | >50 events undelivered |
| [`DeadLetterMessages`](#deadlettermessages) | application | 1m | Anything dead-lettered |
| [`GrpcExecutorSaturated`](#grpcexecutorsaturated) | application | 3m | >90% of gRPC handler threads busy |
| [`GrpcDeadlineExceededRising`](#grpcdeadlineexceededrising) | application | 5m | Callers timing out |
| [`GrpcChannelNotReady`](#grpcchannelnotready) | application | 2m | Channel in `TRANSIENT_FAILURE` |
| [`GrpcCircuitBreakerOpen`](#grpccircuitbreakeropen) | application | 1m | Breaker open |
| [`DatabasePoolExhausted`](#databasepoolexhausted) | database | 2m | Threads waiting for a connection |
| [`PostgresSlowQueries`](#postgresslowqueries) | database | 5m | Transaction running >60s |
| [`OracleSlowQueries`](#oracleslowqueries) | database | 5m | Session executing >60s |
| [`RedisDown`](#redisdown) | cache | 2m | `redis_up == 0` |
| [`RedisHighLatency`](#redishighlatency) | cache | 5m | Mean command >10ms |
| [`RedisMemoryHigh`](#redismemoryhigh) | cache | 10m | >90% of `maxmemory` |
| [`KafkaConsumerLagHigh`](#kafkaconsumerlaghigh) | messaging | 10m | >1000 messages behind |
| [`KafkaConsumerGroupEmpty`](#kafkaconsumergroupempty) | messaging | 5m | A group has no members |
| [`VaultLeaseCountCollapsed`](#vaultleasecountcollapsed) | infrastructure | 5m | No outstanding leases while services are up |
| [`VaultPermissionDenied`](#vaultpermissiondenied) | infrastructure | 5m | Sustained 403s from Vault |

### Information — context only

| Alert | Category | For | Fires when |
| --- | --- | --- | --- |
| [`ServiceRestarted`](#servicerestarted) | infrastructure | 0m | Uptime <5 minutes |
| [`UnusualTrafficVolume`](#unusualtrafficvolume) | application | 10m | >50 req/s sustained |
| [`KafkaConsumerLagGrowing`](#kafkaconsumerlaggrowing) | messaging | 5m | >100 messages behind |

### Grafana rules — the log-based two

Prometheus cannot query Loki, so these live in Grafana. They are **not** duplicates of anything above.

| Alert | Severity | Fires when | Why it cannot be a Prometheus rule |
| --- | --- | --- | --- |
| [`LogErrorRateHigh`](#logerrorratehigh) | warning | >0.5 ERROR lines/s | Log content has no metric. A caught exception that logs and returns 200 is invisible to every counter |
| [`LogPipelineSilent`](#logpipelinesilent) | warning | No lines for 10 minutes | Alerts on the **absence** of data — `noDataState: Alerting` is the entire rule |

---

## 4. Routing

```
                        ┌── severity=critical ──► oncall@  (email, 10s wait, 1h repeat)
                        │                    └──► webhook  (incident manager / chat / automation)
   Prometheus ──► Alertmanager ── severity=warning ──► team@   (email, 1m wait, 6h repeat)
                        │
                        ├── severity=info ─────► digest@ (email, 5m wait, 24h repeat)
                        └── no severity ───────► team@   [unrouted] — a rule is missing a label
```

Grafana's own tree ([`notification-policies.yml`](../infrastructure/grafana/provisioning/alerting/notification-policies.yml))
mirrors it exactly. Two engines, one policy — if they disagreed, an alert's urgency would depend on
which engine happened to evaluate it.

### Grouping

`group_by: [alertname, category, service]`, and **not** by instance. Fifty instances of one problem
is one incident; notifying per instance is how a partial outage becomes an unreadable inbox. You can
see this working in the subject line: `[FIRING:3][WARNING] DiskSpaceFilling` is three alerts in one
email.

### Inhibition — the part that survives a real outage

When PostgreSQL goes down, the error rate, the latency and the connection pool all fire within a
minute. Three of those four notifications say nothing the first one did not.

| When this fires | It suppresses |
| --- | --- |
| Any `critical` | `warning` with the same alertname, category and service |
| `ServiceDown` | Every `application` alert for that service |
| Any `critical` `database` | Every `application` `warning` |
| `KafkaBrokerDown` | `KafkaConsumerLagHigh` |

The `equal:` clause on the first rule is load-bearing. Without it a single critical anywhere would
mute every warning in the system — an inhibition rule that becomes an outage of its own.

---

## 5. Seeing it work

```bash
./scripts/infra.sh up
./scripts/infra.sh urls        # Alertmanager, Mailpit and the webhook sink are listed
```

| What | Where |
| --- | --- |
| Rules and their state | <http://localhost:9090/alerts> |
| Routing, grouping, silences | <http://localhost:9093> |
| **Delivered email** | <http://localhost:8025> |
| **Delivered webhooks** | `docker logs lab-alert-webhook` |
| Firing + delivery dashboard | <http://localhost:3000/d/lab-alerting> |

### Make one fire

The cheapest honest test is to stop something:

```bash
docker compose stop redis          # RedisDown, warning, ~2 minutes
docker compose stop kafka          # KafkaBrokerDown, critical, ~1 minute
```

Then watch it travel: Prometheus `/alerts` → Alertmanager → Mailpit. A critical also lands in the
webhook log.

```bash
# The whole chain in one command
curl -s localhost:9090/api/v1/alerts | jq '.data.alerts[] | {alertname:.labels.alertname, state}'
curl -s localhost:9093/api/v2/alerts | jq '.[] | {alertname:.labels.alertname, status:.status.state}'
curl -s localhost:8025/api/v1/messages | jq '.messages[].Subject'
```

### Silence one

```bash
docker exec lab-alertmanager amtool silence add alertname=RedisDown \
  --duration=1h --comment="planned restart" --alertmanager.url=http://localhost:9093
```

Always with a duration and a comment. A silence with neither is how a rule that should have been
fixed or deleted quietly stops existing — check the *Suppressed and silenced* panel before trusting
a quiet alert channel.

---

## 6. Incident response

The first three steps for each alert. None of these is a full runbook — they are the "what do I open
first" that stops an alert from being a dead end.

### Infrastructure

#### ServiceDown
1. `docker compose ps` / is the JVM process alive? A crash reports nothing at all.
2. Its logs — a startup failure or an OOM kill both look identical from outside.
3. If it restarted by itself, check [`JvmHeapPressure`](#jvmheappressure) history for the minutes before.

#### VaultSealed

**Read this one differently from every other alert in the file.** Nothing is broken *yet*.

1. Confirm: `./scripts/vault.sh status`. `Sealed  true`.
2. **Do not go looking for a symptom.** There is none. Every running service keeps serving from the
   secrets and connections it already holds — latency flat, error rate flat, health green. The stack
   breaks at the next lease renewal or the next service restart, which may be an hour away.
3. `./scripts/vault.sh unseal`.
4. Confirm with `./scripts/vault.sh whoami` — the Order Service should be connected to PostgreSQL as
   a `v-approle-order-se-…` user.

If the keys file is gone, this Vault cannot be opened. See [Vault.md §4](Vault.md#4-the-bootstrap-problem).

#### VaultLeaseCountCollapsed
1. `./scripts/vault.sh leases` — empty confirms it.
2. The Order Service's dynamic PostgreSQL credential was revoked or expired. Open connections still
   work; the next one Hikari opens will not.
3. `docker restart lab-order-service` re-leases at startup.
4. If it keeps happening, check that `spring.cloud.vault.config.lifecycle.enabled` is still `true` —
   without renewal the credential dies at its 1h TTL every time.

#### VaultPermissionDenied
1. `./scripts/vault.sh audit 50` — the denied path is named in the log.
2. Most common cause after a config change: a new `SPRING_PROFILES_ACTIVE`. Spring Cloud Vault also
   reads `secret/data/<service>/<profile>`, and Vault answers an ungranted path with **403, not 404** —
   so a missing grant and a missing secret are indistinguishable.
3. `./scripts/vault.sh deny` confirms the boundary still behaves as designed.

#### VaultAuditDeviceFailing
1. **This is a total outage of the secret store, not a degradation.** Vault refuses *every* request
   once all audit devices are failing — deliberately, on the grounds that an unauditable secret store
   is worse than an unavailable one.
2. The cause is almost always disk: `docker exec lab-vault df -h /vault/logs`, then `docker system prune`.
3. It is startling the first time because the symptom is Vault being down and the cause is a log file.

#### ExporterDown
1. Which exporter, and what alerts depend on it — **those alerts cannot fire while this is true.**
2. Restart it, then confirm the target is UP at <http://localhost:9090/targets>.
3. If it will not connect, its monitoring credential is the usual cause. See §7.

#### HighCpuUsage
1. Did latency move with it? If not, this is capacity in use, not a fault.
2. Profiles → CPU flame graph for the service that grew.
3. Consider whether `UnusualTrafficVolume` also fired — the answer is usually there.

#### HighMemoryUsage
1. Container limits first: `docker stats`.
2. Then the JVM heap panel — the JVM is normally the largest target of an OOM kill.
3. Profiles → live heap, not allocation. Allocation volume says nothing about what is retained.

#### DiskSpaceFilling
1. `docker system df` — image and volume sprawl is the usual answer in a lab.
2. `logs/` in the repository; the JSON logs roll but a stuck shipper lets them accumulate.
3. Nothing is broken yet. This is the ticket that stops the page below from happening.

#### DiskSpaceCritical
1. Free space **now**: `docker system prune`, then old volumes.
2. `./scripts/infra.sh destroy` resets all lab state, including the databases.
3. PostgreSQL and Oracle stop accepting writes when they cannot extend a datafile, and they do it
   abruptly rather than degrading — so this is a page and the one above is not.

#### ServiceRestarted
Nothing, if it was intended. If it was not, treat it as the *first* symptom and read the alerts
around it — this is context, which is why it is `info`.

### Application

#### HighHttpFaultRate
1. HTTP dashboard → responses by outcome → which endpoint.
2. Traces → an error span → the log line by `trace_id`.
3. Check whether a `database` or `messaging` critical fired first. It usually did, and this is the
   consequence.

#### HighHttpLatency
1. Databases dashboard → threads waiting for a connection.
2. JVM → GC pause time.
3. Traces → slowest spans, then Profiles for the method.

#### GrpcHighFaultRate
1. gRPC dashboard → status distribution. A business status miscategorised as a fault is the usual
   cause, and the fix is the mapping rather than the alert.
2. If they are genuine faults: Traces → the failing RPC → the handler's log line.
3. See [Grpc.md §5.2](Grpc.md) for why `INTERNAL` versus `UNAVAILABLE` changes the retry behaviour.

#### UnusualTrafficVolume
Nothing. This is `info` — it exists so that the latency and saturation alerts around it can be read
in the right light. Most "the system got slower" questions turn out to be "the system got busier".

#### TomcatThreadPoolSaturated
1. What are the threads blocked on? Database pool waits first.
2. Then a slow downstream call — the gRPC client/server duration gap.
3. Raising the pool size is the last resort, not the first: a bigger pool blocked on the same thing
   fails the same way, more expensively.

#### JvmHeapPressure
1. Profiles → **live heap**, which is what finds a leak. Allocation volume is mostly short-lived
   garbage the collector reclaims for free and says nothing about what is retained.
2. Heap by pool: a full Eden is a healthy collector; a full Old that never drops is not.
3. Correlate with `UnusualTrafficVolume` before concluding it is a leak.

#### JvmGcPauseTimeHigh
1. The heap panel first — this is usually heap pressure wearing a different hat.
2. If the heap is comfortable, the allocation *rate* is the suspect: Profiles → allocation.
3. Every request pays the pause, so expect latency to have risen across the board rather than on
   one endpoint. If it has not, this is not yet worth acting on.

#### OrdersAcceptedButNotSettled
The business alert with no technical equivalent — every process metric can be green.
1. Kafka consumer lag.
2. The outbox backlog.
3. The Inventory Service's logs. If it is up and consuming, the reservation itself is failing.

#### OutboxBacklogGrowing
1. Is Kafka reachable *from the service* — not from your machine?
2. The relay's logs.
3. Nothing is lost while this is firing. The outbox is durable; it drains when the broker returns.

#### DeadLetterMessages
1. Inspect `dead-letter-topic` in Kafka UI (<http://localhost:8090>).
2. Understand why before replaying. **Never replay blindly** — a poison message on a loop is an
   outage.
3. Whatever those messages represent has not happened and nothing will retry it.

#### GrpcExecutorSaturated
The failure with no other symptom: slow calls, idle CPU, healthy heap, empty Tomcat pool.
1. gRPC dashboard → executor pool.
2. What are the handlers blocked on? Oracle, usually.
3. See [Grpc.md §6.3](Grpc.md).

#### GrpcDeadlineExceededRising
1. Compare the client and server duration panels. **A large gap is the channel; a small one is the
   handler.**
2. Server fast ⇒ the deadline is too tight, or the channel is queueing. Server slow ⇒ Profiles.
3. Nothing is lost while this fires: availability degrades to unknown and reservations defer to
   Kafka.

#### GrpcChannelNotReady
1. Consul catalog: are the `inventory-service` instances passing?
2. Do they advertise `grpc-port` metadata? An instance without it is filtered out by the resolver,
   and if none have it the channel has nowhere to connect.
3. See [Grpc.md §4](Grpc.md).

#### GrpcCircuitBreakerOpen
1. Why did it trip — failure rate, or slow-call rate? They mean different things.
2. **Business statuses must never open it.** If `NOT_FOUND` is being counted, the configuration is
   wrong, not the dependency.
3. The fallbacks are working while this fires. See [Grpc.md §5.4](Grpc.md).

### Databases

#### PostgresDown
1. `docker compose ps postgres`, then its container logs.
2. Disk space — a database that cannot extend a datafile stops abruptly rather than degrading.
3. If the server is healthy, suspect the exporter's credential rather than the database: see §7.

#### OracleDown
1. `docker compose ps oracle`. Oracle is **slow** to become healthy — confirm it is not still
   starting before treating this as an outage.
2. Its container logs, then disk space.
3. Same caveat as above: a missing `LAB_MONITOR` user reports as the database being down.

#### PostgresSlowQueries
1. `pg_stat_activity` — the statement **and** its wait event. A *blocked* transaction is far more
   common than a genuinely slow one.
2. It holds locks and a connection while it runs, so `DatabasePoolExhausted` usually follows.
3. Killing it is a decision, not a first step: find out what it is waiting on first.

#### OracleSlowQueries
1. `v$session` for the `SQL_ID`, then `v$sql` for the statement text.
2. Check for a missing index on `stock_levels` — the reservation path is the one that matters.
3. It holds a connection from a pool of ten, so expect pool pressure to follow.

#### DatabasePoolExhausted
1. Is the server itself healthy? If yes, the application is holding connections too long.
2. Look for a slow query inside a transaction, or a remote call made while one is open.
3. This is the shape almost every "the site is slow" incident actually has.

### Cache

#### RedisDown
**Degraded, not broken.** Every cache here has a defined miss path to a database — which is exactly
why this is a warning.
1. Restart it.
2. Watch database pool pressure while it is down; that is where the load went.

#### RedisHighLatency
1. Look for an O(N) command — `KEYS`, or a large `SMEMBERS`.
2. Redis is single-threaded: one slow command delays every client behind it.

#### RedisMemoryHigh
1. Eviction policy and TTLs. A cache entry with no TTL is a memory leak with a delay fuse.
2. Eviction has already begun by the time this fires; expect the hit ratio to fall.

### Messaging

#### KafkaBrokerDown
1. `docker compose ps kafka`.
2. Orders are still being accepted — the outbox holds their events. Nothing is lost.
3. Do **not** replay anything by hand. The outbox drains itself when the broker returns.

#### KafkaConsumerLagHigh
1. Is the consumer alive and not stuck rebalancing?
2. Did its handler slow down? Oracle first.
3. `KafkaConsumerGroupEmpty` firing alongside means it is not consuming at all — a different problem
   with a different fix.

#### KafkaConsumerLagGrowing
Nothing yet. This is `info`: lag rising off zero is normal under a burst, and the rule exists so
that `KafkaConsumerLagHigh` has a visible beginning rather than appearing from nowhere.

#### KafkaConsumerGroupEmpty
1. The consuming service is down, or failed to join the group.
2. Its logs at startup — a deserialisation failure at assignment is the classic cause.
3. Lag will grow silently from here, so this is more urgent than the number suggests.

### Logs

#### LogErrorRateHigh
1. Explore → Loki: `{service="...", level="ERROR"}`, grouped by logger. There is no logs dashboard
   by design — arriving with an identifier and following it is what Explore is for.
2. Follow one `trace_id` into Tempo.
3. The HTTP fault rate may be entirely within threshold — an exception that is caught, logged and
   returns 200 is invisible to every counter.

#### LogPipelineSilent
1. Is anything actually serving traffic? An idle lab produces no logs, and that is the common case.
2. If yes: is Promtail running, is `logs/` being written, is it mounted into the container?
3. **While this fires, every log-based alert is unable to fire.** Absence of alerts is not health.

---

## 7. Operating it

### The monitoring credentials

The exporters use read-only accounts that cannot see one row of application data:

| Datastore | Account | Grant |
| --- | --- | --- |
| PostgreSQL | `lab_monitor` | `pg_monitor` — the `pg_stat_*` views and nothing else |
| Oracle | `LAB_MONITOR` | `SELECT_CATALOG_ROLE` — the data dictionary and `V$` views |

They are created by the datastore init scripts, which **run only on first initialisation of an empty
volume**. On an existing stack the exporter reports down and its logs say authentication failed. Fix
it with `./scripts/infra.sh destroy` (deletes all data), or create the accounts by hand:

```bash
# PostgreSQL — note the -i, or the heredoc goes nowhere
set -a && . docker/compose/.env && set +a
docker exec -i -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" lab-postgres \
  psql -U "$POSTGRES_SUPERUSER" -d "$POSTGRES_SUPERUSER_DB" <<SQL
CREATE ROLE "${MONITOR_DB_USER}" WITH LOGIN PASSWORD '${MONITOR_DB_PASSWORD}'
  NOSUPERUSER NOCREATEDB NOCREATEROLE;
GRANT pg_monitor TO "${MONITOR_DB_USER}";
SQL
docker restart lab-postgres-exporter
```

```bash
# Oracle
docker exec -i lab-oracle sqlplus -s -L "sys/${ORACLE_SYS_PASSWORD}@localhost:1521/${ORACLE_PDB} as sysdba" <<SQL
CREATE USER ${ORACLE_MONITOR_USER} IDENTIFIED BY "${ORACLE_MONITOR_PASSWORD}";
GRANT CREATE SESSION, SELECT_CATALOG_ROLE TO ${ORACLE_MONITOR_USER};
EXIT
SQL
docker restart lab-oracle-exporter
```

### Adding an alert

1. Put it in the rule file for its subsystem under `infrastructure/prometheus/rules/`.
2. Give it `severity` **and** `category`, or it reaches the unrouted fallback.
3. Give it `summary`, `description`, `impact`, `action`, `runbook`, `dashboard`. All six. An alert
   without `action` is one that will be escalated to somebody who also does not know.
4. Add a `for:` duration. A rule with no `for:` flaps, and a flapping alert is worse than a missing
   one.
5. Add a row to §3 and a response to §6 in this file.
6. Validate before reloading:

```bash
docker run --rm -v "$PWD/infrastructure/prometheus/rules:/rules" \
  --entrypoint sh prom/prometheus:v3.7.3 -c 'promtool check rules /rules/*.yml'
docker exec lab-prometheus wget -q -O- --post-data='' http://127.0.0.1:9090/-/reload
```

### Changing routing

`infrastructure/alertmanager/alertmanager.yml`, then:

```bash
docker run --rm -v "$PWD/infrastructure/alertmanager:/etc/alertmanager" \
  --entrypoint amtool prom/alertmanager:v0.28.1 check-config /etc/alertmanager/alertmanager.yml
docker restart lab-alertmanager
```

Recipients and URLs are **literals** in that file, not `${VARIABLES}`. Alertmanager does not expand
environment variables in its configuration, and a `${VAR}` there is handed to the SMTP server as a
recipient address.

---

## 8. What is deliberately not alerted on

| Not alerted | Why |
| --- | --- |
| 4xx responses | The caller's bug, not ours. `lab:http_error_rate` includes them for dashboards; the alert uses `lab:http_fault_rate`, which is 5xx only |
| `NOT_FOUND`, `FAILED_PRECONDITION`, insufficient stock | Business answers. The system is working. Counting them makes the error rate track catalogue quality |
| Individual slow requests | One slow request is a trace, not an incident. The p99 is the alert |
| Cache misses | A miss is the cache working as designed |
| Kong and Nginx | Neither is scraped yet — the edge is invisible to this whole system. A revision of step 06 |
| Trace or profile ingestion | Worth having, and it belongs with a revision of step 12 rather than bolted on here |
| Anything with no defined response | If §6 would say "look at it and see", it is a dashboard panel |

### Known gaps

- **No paging integration.** The webhook sink logs the payload; a real deployment points it at an
  incident manager. The payload shape is identical, which is the point of using a real webhook
  receiver rather than a mock.
- **No alert on the alerting.** `ExporterDown` covers the exporters and Prometheus scrapes
  Alertmanager, but nothing external watches Prometheus itself. In a real deployment a second
  Prometheus watches the first, or a dead-man's-switch alert fires *continuously* into a service
  that pages when it **stops** arriving.
- **`for: 0m` on `ServiceRestarted`.** Intentional — the condition is a five-minute window already —
  but it is the one rule here that can notify on a single scrape.
