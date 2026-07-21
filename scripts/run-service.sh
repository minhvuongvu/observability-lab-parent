#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Runs a service against the local infrastructure stack.
#
#     ./scripts/run-service.sh order-service
#     ./scripts/run-service.sh order-service --spring.profiles.active=dev
#
# Connection settings are read from docker/compose/.env, which is the single
# source of truth for how the stack is published on this machine. That matters:
# a host already running PostgreSQL or Redis makes the lab move to other ports,
# and hard-coding 5432 into a run command would then silently connect a service
# to the wrong database.
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

# Translate the stack's published addresses into the variables the services
# resolve in their application.yml.
export ORDER_DB_HOST="${HOST}"
export ORDER_DB_PORT="${POSTGRES_PORT:-5432}"
export REDIS_HOST="${HOST}"
export REDIS_PORT="${REDIS_PORT:-6379}"
export KAFKA_BOOTSTRAP_SERVERS="${HOST}:${KAFKA_EXTERNAL_PORT:-9092}"
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
# Derived from the published port for the same reason as the rest: a host
# already running something on 8080 moves Keycloak, and a service left
# validating tokens against the wrong issuer rejects every request with an
# error that says nothing about ports.
export KEYCLOAK_ISSUER="http://${HOST}:${KEYCLOAK_PORT:-8080}/realms/${KEYCLOAK_REALM:-observability}"
export KEYCLOAK_JWKS_URI="${KEYCLOAK_ISSUER}/protocol/openid-connect/certs"

# The address this service advertises in Consul, and therefore the address other
# services are handed when they resolve it through discovery. It has to work
# from two places at once: from inside the Consul container, which HTTP
# health-checks it, and from the host, where the other services actually run.
#
# host.docker.internal satisfies only the first - it does not resolve on the
# host - so a Feign client following the registry would be given an address it
# cannot reach. This machine's own LAN address satisfies both. The
# host.docker.internal fallback remains correct where no LAN address can be
# determined, and the whole variable becomes the compose service name once the
# services are themselves containerised.
if [ -z "${SERVICE_HOSTNAME:-}" ]; then
  SERVICE_HOSTNAME="$( { ipconfig getifaddr en0 || ipconfig getifaddr en1; } 2>/dev/null \
    || hostname -I 2>/dev/null | awk '{print $1}' \
    || true)"
fi
export SERVICE_HOSTNAME="${SERVICE_HOSTNAME:-host.docker.internal}"

# Absolute, and inside the repository: Promtail, Fluent Bit and Fluentd all
# bind-mount this directory to tail the JSON logs. A relative path would put the
# files wherever the service happened to be started from, which is exactly the
# kind of thing that makes a log pipeline mysteriously empty.
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
