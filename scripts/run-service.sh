#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Runs ONE service on the host, against the containerised stack.
#
#     docker stop lab-order-service          # first: retire its container
#     ./scripts/run-service.sh order-service
#
# THIS IS THE EXCEPTION PATH, not how the lab runs.
#
# Everything - both services, the observability stack, the load generator and
# the fault proxy - runs inside the lab-net Docker network:
#
#     ./scripts/infra.sh up
#
# This script exists for one thing the container cannot give you conveniently:
# attaching an IDE debugger with hot reload to a service while the rest of the
# system carries on around it. Its container must be stopped first, or two
# instances register in Consul and half the traffic goes to the one you are not
# looking at - which is a genuinely baffling afternoon.
#
# What you give up by using it, and it is worth knowing before you do:
#
#   - The service is outside lab-net, so its traffic crosses the host's network
#     stack. Latency measurements are not comparable with a containerised run.
#   - Toxiproxy is not in its path, so ./scripts/chaos.sh cannot inject faults
#     into this instance's dependencies.
#   - There is no CPU or memory limit on it, so it will not exhibit the
#     saturation, GC pressure or OOM behaviour the container is tuned to show.
#
# Connection settings come from docker/compose/.env, translated below from the
# in-network addresses the containers use into the published ports the host can
# actually reach. That translation is the whole body of this script.
#
# Any argument after the service name is passed through to the application.
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# shellcheck source=scripts/toolchain.sh
source "${SCRIPT_DIR}/toolchain.sh"
resolve_java_home

SERVICE="${1:-}"
if [ -z "${SERVICE}" ]; then
  echo "usage: $(basename "$0") <order-service|inventory-service> [application args...]" >&2
  exit 1
fi
shift

ENV_FILE="${REPO_ROOT}/docker/compose/.env"
if [ ! -f "${ENV_FILE}" ]; then
  echo "ERROR: ${ENV_FILE} does not exist. Start the stack first: ./scripts/infra.sh up" >&2
  exit 1
fi

# shellcheck disable=SC1090
set -a; . "${ENV_FILE}"; set +a

HOST="${BIND_HOST:-127.0.0.1}"

# ---------------------------------------------------------------------------
# Address translation.
#
# The variables in .env name addresses on lab-net - `toxiproxy:15432`,
# `kafka:9092` - because that is what the containers use. None of them resolve
# on the host, so each is rebuilt here from the PUBLISHED port of the same
# component. Every assignment below is deliberately overriding what .env set.
#
# Note that nothing here goes through Toxiproxy: its proxy ports are not
# published, and there would be little point since the traffic has already left
# the network. Fault injection does not reach a service run this way.
# ---------------------------------------------------------------------------
export ORDER_DB_HOST="${HOST}"
export ORDER_DB_PORT="${POSTGRES_PORT:-5432}"
export REDIS_HOST="${HOST}"
export REDIS_PORT="${REDIS_HOST_PORT:-6379}"
# The broker's EXTERNAL listener, which advertises localhost - the reason that
# listener exists at all.
export KAFKA_BOOTSTRAP_SERVERS="${HOST}:${KAFKA_EXTERNAL_PORT:-29092}"
export ORACLE_DB_HOST="${HOST}"
export ORACLE_DB_PORT="${ORACLE_PORT:-1521}"
export CONSUL_HOST="${HOST}"
export CONSUL_PORT="${CONSUL_PORT:-8500}"
export MINIO_ENDPOINT="http://${HOST}:${MINIO_API_PORT:-9000}"
# The bucket user, not the root account: the services hold credentials scoped
# to the invoice bucket and nothing else.
export MINIO_ACCESS_KEY="${MINIO_APP_USER:-lab-invoice-app}"
export MINIO_SECRET_KEY="${MINIO_APP_PASSWORD:-}"
export MINIO_INVOICE_BUCKET="${MINIO_INVOICE_BUCKET:-invoices}"
# Identity, and the one pair that must NOT be kept consistent with each other.
#
# The issuer is an in-network address and stays exactly as .env has it: every
# token in this lab is minted with `iss` = http://keycloak:8080/realms/... -
# by k6, by scripts/token.sh, by the containerised services - and a host-run
# instance validating against anything else rejects all of them.
#
# The JWKS endpoint is where this process actually fetches verification keys
# from, so it has to be an address the host can resolve. Different values, and
# correctly so: one is a string to compare, the other is a URL to open.
export KEYCLOAK_ISSUER="${KEYCLOAK_ISSUER:-http://keycloak:8080/realms/${KEYCLOAK_REALM:-observability}}"
export KEYCLOAK_JWKS_URI="http://${HOST}:${KEYCLOAK_PORT:-8080}/realms/${KEYCLOAK_REALM:-observability}/protocol/openid-connect/certs"

# The address this service advertises in Consul, and therefore the address other
# services are handed when they resolve it through discovery. It has to work
# from two places at once: from inside the Consul container, which HTTP
# health-checks it, and from the host, where the other services actually run.
#
# host.docker.internal satisfies only the first - it does not resolve on the
# host - so a Feign client following the registry would be given an address it
# cannot reach. This machine's own LAN address satisfies both.
#
# The containerised instance of this service registers its compose name instead,
# which is why its container has to be stopped before this one starts: two
# instances under the same service id split the traffic between them.
if [ -z "${SERVICE_HOSTNAME:-}" ]; then
  SERVICE_HOSTNAME="$( { ipconfig getifaddr en0 || ipconfig getifaddr en1; } 2>/dev/null \
    || hostname -I 2>/dev/null | awk '{print $1}' \
    || true)"
fi
export SERVICE_HOSTNAME="${SERVICE_HOSTNAME:-host.docker.internal}"

# ---------------------------------------------------------------------------
# gRPC.
#
# The Inventory Service serves inventory.v1.InventoryService on its own port,
# alongside its REST API. The port is registered in Consul as metadata, and the
# Order Service's channel resolves it from there - so this address has to be
# reachable from the *host*, where the other service actually runs, and not only
# from inside a container.
#
# Bound to the same interface the service advertises for exactly that reason:
# leaving it on loopback while advertising a LAN address would register an
# endpoint nothing outside this process can connect to.
# ---------------------------------------------------------------------------
if [ "${SERVICE}" = "inventory-service" ]; then
  export GRPC_PORT="${GRPC_PORT:-9082}"
  export GRPC_BIND_ADDRESS="${GRPC_BIND_ADDRESS:-0.0.0.0}"
fi

# Absolute, and inside the repository. Note what this means now that the agents
# read the lab-logs volume rather than this directory: logs written here are NOT
# shipped to Loki. They are readable on disk and nowhere else.
#
# That is a real limitation of the debug path rather than an oversight - the
# alternative is mounting a host directory back into three containers, which
# reintroduces exactly the host dependency this stack was restructured to
# remove. If you need this instance's logs in Grafana, run it in its container.
export LOG_DIR="${LOG_DIR:-${REPO_ROOT}/logs}"
mkdir -p "${LOG_DIR}"

# ---------------------------------------------------------------------------
# Tracing.
#
# The OpenTelemetry Java agent instruments the application from outside it:
# Spring MVC, JDBC (PostgreSQL and Oracle alike), the Kafka producer and
# consumer, Lettuce, Feign and MinIO's HTTP client all become spans without a
# line of application code. That breadth is the reason for an agent rather than
# a library - JDBC and the object-storage client in particular have no
# Spring-native instrumentation to switch on.
#
# Set OTEL_SDK_DISABLED=true to run without it.
# ---------------------------------------------------------------------------
JAVA_AGENT_OPTS=()
if [ "${OTEL_SDK_DISABLED:-false}" != "true" ]; then
  AGENT_JAR="$("${SCRIPT_DIR}/agents.sh" otel)"
  JAVA_AGENT_OPTS=(-javaagent:"${AGENT_JAR}")

  # Identity. Matches the service/environment/version on every log line and
  # metric, so all three signals are filterable by the same vocabulary.
  export OTEL_SERVICE_NAME="${SERVICE}"
  export OTEL_RESOURCE_ATTRIBUTES="service.name=${SERVICE},service.namespace=observability-lab,deployment.environment=${OTEL_ENVIRONMENT:-local}"

  # Everything goes to the collector, which fans out to Tempo, Jaeger and
  # Zipkin. Pointing the application at one endpoint rather than three is the
  # entire argument for running a collector.
  export OTEL_EXPORTER_OTLP_ENDPOINT="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://${HOST}:${OTLP_GRPC_PORT:-4317}}"
  export OTEL_EXPORTER_OTLP_PROTOCOL="${OTEL_EXPORTER_OTLP_PROTOCOL:-grpc}"

  # Traces only. The agent can also export metrics and logs, but Prometheus
  # already scrapes the former and the file pipeline already carries the
  # latter; turning both on would double-count every signal.
  export OTEL_TRACES_EXPORTER="${OTEL_TRACES_EXPORTER:-otlp}"
  export OTEL_METRICS_EXPORTER="${OTEL_METRICS_EXPORTER:-none}"
  export OTEL_LOGS_EXPORTER="${OTEL_LOGS_EXPORTER:-none}"

  # Sample everything. Correct for a lab, and wrong for production: at real
  # volume this is where a sampling strategy goes instead.
  export OTEL_TRACES_SAMPLER="${OTEL_TRACES_SAMPLER:-parentbased_always_on}"

  # Puts trace_id and span_id into the MDC, which is what makes a log line
  # clickable through to its trace. The key names match this platform's log
  # schema, so no translation is needed.
  export OTEL_INSTRUMENTATION_LOGBACK_MDC_ENABLED=true
  export OTEL_INSTRUMENTATION_COMMON_MDC_TRACE_ID_KEY=trace_id
  export OTEL_INSTRUMENTATION_COMMON_MDC_SPAN_ID_KEY=span_id

  # Pyroscope's OpenTelemetry extension, loaded *by* the OTel agent rather than
  # as a third -javaagent. It stamps the active span id onto the profiling
  # labels, which is what turns "this span was slow" into "and here is the
  # flame graph of the CPU it burned". Without it, traces and profiles are two
  # unrelated views of the same process.
  if [ "${PYROSCOPE_ENABLED:-true}" = "true" ]; then
    PYROSCOPE_OTEL_JAR="$("${SCRIPT_DIR}/agents.sh" pyroscope-otel)"
    export OTEL_JAVAAGENT_EXTENSIONS="${PYROSCOPE_OTEL_JAR}"
  fi
fi

# ---------------------------------------------------------------------------
# Continuous profiling.
#
# A second agent, wrapping async-profiler. It answers the question neither a
# trace nor a metric can: *which lines of code* were burning the CPU, allocating
# the heap or holding the locks - continuously, in production, at a sampling
# cost low enough to leave running.
#
# Set PYROSCOPE_ENABLED=false to run without it.
# ---------------------------------------------------------------------------
if [ "${PYROSCOPE_ENABLED:-true}" = "true" ]; then
  PYROSCOPE_JAR="$("${SCRIPT_DIR}/agents.sh" pyroscope)"
  JAVA_AGENT_OPTS+=(-javaagent:"${PYROSCOPE_JAR}")

  export PYROSCOPE_APPLICATION_NAME="${SERVICE}"
  export PYROSCOPE_SERVER_ADDRESS="${PYROSCOPE_SERVER_ADDRESS:-http://${HOST}:${PYROSCOPE_PORT:-4040}}"

  # JFR rather than the single-event collapsed format. This is the setting that
  # makes all four profile types available at once: with the collapsed format
  # the agent can record exactly one event, so choosing CPU means giving up
  # allocation and lock data entirely.
  export PYROSCOPE_FORMAT=jfr
  # The names are PYROSCOPE_PROFILER_*, not PYROSCOPE_PROFILING_*. Getting them
  # wrong is silent: the agent starts, uploads happily, and delivers CPU
  # profiles only - the allocation and lock views simply never appear.
  #
  # itimer rather than cpu: it samples on a wall-clock timer, so it also
  # attributes time spent blocked, which `cpu` (perf events) does not. On a
  # service that spends most of its life waiting on a database, `cpu` produces
  # a flame graph of almost nothing.
  export PYROSCOPE_PROFILER_EVENT="${PYROSCOPE_PROFILER_EVENT:-itimer}"
  # Sample an allocation profile every 512 KB allocated, and any lock held for
  # more than 10 ms. Both are thresholds, not switches: sampling every
  # allocation would cost more than the application.
  export PYROSCOPE_PROFILER_ALLOC="${PYROSCOPE_PROFILER_ALLOC:-512k}"
  export PYROSCOPE_PROFILER_LOCK="${PYROSCOPE_PROFILER_LOCK:-10ms}"
  # Live-object profiling, which is a different question from allocation.
  # `alloc` answers "what allocated the most", and most of that is short-lived
  # garbage the collector reclaims without cost. `alloc_live` answers "what is
  # still holding memory" - which is the one that finds a leak.
  export PYROSCOPE_ALLOC_LIVE="${PYROSCOPE_ALLOC_LIVE:-true}"

  # Labels, matching the tag vocabulary used by logs, metrics and traces so a
  # profile can be filtered by the same words as everything else.
  export PYROSCOPE_LABELS="service=${SERVICE},environment=${OTEL_ENVIRONMENT:-local}"

  # How often profiles are shipped. Ten seconds keeps a flame graph responsive
  # while looking at something happening now, without a request per second.
  export PYROSCOPE_UPLOAD_INTERVAL="${PYROSCOPE_UPLOAD_INTERVAL:-10s}"
fi

JAR="$(find "${REPO_ROOT}/services/${SERVICE}/target" -maxdepth 1 -name "${SERVICE}-*.jar" \
        ! -name '*.original' 2>/dev/null | head -1)"
if [ -z "${JAR}" ]; then
  echo "ERROR: no jar for '${SERVICE}'. Build it first: ./scripts/build.sh" >&2
  exit 1
fi

echo "Starting ${SERVICE}"
echo "  database  ${ORDER_DB_HOST}:${ORDER_DB_PORT}"
echo "  redis     ${REDIS_HOST}:${REDIS_PORT}"
echo "  kafka     ${KAFKA_BOOTSTRAP_SERVERS}"
echo "  consul    ${CONSUL_HOST}:${CONSUL_PORT}"
echo "  minio     ${MINIO_ENDPOINT} (bucket ${MINIO_INVOICE_BUCKET})"
echo "  issuer    ${KEYCLOAK_ISSUER}"
echo "  registers as ${SERVICE_HOSTNAME}"
if [ -n "${GRPC_PORT:-}" ]; then
  echo "  grpc      ${GRPC_BIND_ADDRESS}:${GRPC_PORT} (inventory.v1.InventoryService)"
fi
echo "  json logs ${LOG_DIR}/${SERVICE}.json"
if [ "${OTEL_SDK_DISABLED:-false}" != "true" ]; then
  echo "  traces    ${OTEL_EXPORTER_OTLP_ENDPOINT} (otlp/${OTEL_EXPORTER_OTLP_PROTOCOL})"
else
  echo "  traces    disabled (OTEL_SDK_DISABLED=true)"
fi
if [ "${PYROSCOPE_ENABLED:-true}" = "true" ]; then
  echo "  profiles  ${PYROSCOPE_SERVER_ADDRESS} (cpu, alloc, lock)"
else
  echo "  profiles  disabled (PYROSCOPE_ENABLED=false)"
fi
echo

# java_bin resolves java or java.exe; the bare path does not exist on Windows.
JAVA="$(java_bin)"
[ -n "${JAVA}" ] || { echo "ERROR: no java launcher under ${JAVA_HOME}" >&2; exit 1; }

exec "${JAVA}" "${JAVA_AGENT_OPTS[@]}" -jar "${JAR}" "$@"
