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
echo

# java_bin resolves java or java.exe; the bare path does not exist on Windows.
JAVA="$(java_bin)"
[ -n "${JAVA}" ] || { echo "ERROR: no java launcher under ${JAVA_HOME}" >&2; exit 1; }

exec "${JAVA}" -jar "${JAR}" "$@"
