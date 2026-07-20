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
echo

# java_bin resolves java or java.exe; the bare path does not exist on Windows.
JAVA="$(java_bin)"
[ -n "${JAVA}" ] || { echo "ERROR: no java launcher under ${JAVA_HOME}" >&2; exit 1; }

exec "${JAVA}" -jar "${JAR}" "$@"
