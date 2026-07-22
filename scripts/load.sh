#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Runs a k6 load scenario from inside the Docker network.
#
#   ./scripts/load.sh smoke      1 VU, 1 minute - is the system wired up?
#   ./scripts/load.sh load       ramp to 10 orders/s and hold for 5 minutes
#   ./scripts/load.sh stress     climb to 100/s until something gives
#   ./scripts/load.sh spike      idle, then 80/s in ten seconds, then idle
#   ./scripts/load.sh soak       5/s for two hours - finds what accumulates
#
# Those defaults are MEASURED, not chosen: the lab saturates at around 10
# orders/s against a 10-connection pool and a 1.5-CPU limit. Raising a limit
# invalidates them - see docs/Performance.md.
#
# Every scenario takes environment overrides:
#
#   RATE=200 DURATION=10m ./scripts/load.sh load
#   PEAK=1500 ./scripts/load.sh stress
#   BASE_URL=http://nginx ./scripts/load.sh load     # through the edge
#
# k6 runs as a container on lab-net, not on the host. It therefore reaches the
# services over the same network they use to reach each other - rather than
# through the host's loopback stack, which has different latency
# characteristics, no notion of the gateway, and would put the load generator
# outside the system it is measuring.
#
# Results are remote-written into the same Prometheus that scrapes the platform,
# so the load and its effect share one time axis: k6_http_req_duration beside
# jvm_gc_pause_seconds beside hikaricp_connections_pending, at the same instant.
#
# Combine with faults - they behave nothing alike on an idle system:
#
#   ./scripts/load.sh load &
#   sleep 60 && ./scripts/chaos.sh slow postgres 400
# ---------------------------------------------------------------------------
set -euo pipefail

export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_DIR="${REPO_ROOT}/docker/compose"

SCENARIOS="smoke load stress spike soak"

die() { echo "ERROR: $*" >&2; exit 1; }

ENV_FILE="${COMPOSE_DIR}/.env"
if [ -f "${ENV_FILE}" ]; then
  # Only to print accurate URLs below. The variables the k6 container actually
  # reads come from compose, which loads this same file itself.
  # shellcheck disable=SC1090
  set -a; . "${ENV_FILE}"; set +a
fi

SCENARIO="${1:-}"
case " ${SCENARIOS} " in
  *" ${SCENARIO} "*) shift ;;
  *) echo "usage: $(basename "$0") <${SCENARIOS// /|}> [k6 args...]" >&2
     echo >&2
     sed -n '5,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
     exit 1 ;;
esac

command -v docker >/dev/null 2>&1 || die "Docker is not installed."
docker compose version >/dev/null 2>&1 || die "The 'docker compose' plugin is not available."

# The services have to be up, or the run measures nothing but connection
# refusals and reports a wall of red that says nothing about the platform.
docker inspect --format '{{.State.Status}}' lab-order-service 2>/dev/null \
  | grep -q running || die \
"lab-order-service is not running. Start the stack first: ./scripts/infra.sh up"

compose() {
  (cd "${COMPOSE_DIR}" && docker compose \
      -f docker-compose.yml \
      -f docker-compose.platform.yml \
      -f docker-compose.observability.yml \
      -f docker-compose.services.yml \
      -f docker-compose.simulation.yml \
      --profile load "$@")
}

# How many faults are currently active. Printed because a load test run against
# a stack that still has a toxic from an earlier experiment produces numbers
# that look like a regression and are an artefact - and that is a genuinely
# easy hour to lose.
ACTIVE_TOXICS="$("${SCRIPT_DIR}/chaos.sh" list 2>/dev/null | grep -c '"type"' || true)"

echo "Running the '${SCENARIO}' scenario."
echo "  target         ${BASE_URL:-${K6_BASE_URL:-http://order-service:8081}}"
echo "  Grafana        http://${BIND_HOST:-127.0.0.1}:${GRAFANA_PORT:-3000}"
echo "  k6 metrics     remote-written to Prometheus as k6_*"
echo "  active toxics  ${ACTIVE_TOXICS:-0}"
if [ "${ACTIVE_TOXICS:-0}" -gt 0 ] 2>/dev/null; then
  echo "                 ^ faults are active. ./scripts/chaos.sh reset clears them."
fi
echo

# `run --rm`, not `up`: a load test has a beginning and an end, and one left
# running would poison every other measurement the lab takes.
#
# K6_OUTPUT is what lands the results in Prometheus; the server accepts them
# because it runs with --web.enable-remote-write-receiver, which Tempo's metrics
# generator already needed. The output name is a variable because it is still
# flagged experimental in k6 1.x and is expected to be renamed when it
# graduates - at which point this is a change to .env, not to this script.
#
# The environment overrides are passed through explicitly rather than with
# `--env-file`, so a variable set on this command line wins over the value in
# docker/compose/.env - which is the behaviour the usage examples above imply.
# Not `exec`: compose is a shell function here, and exec replaces the shell with
# a program rather than calling one.
compose run --rm \
  ${RATE:+-e RATE="${RATE}"} \
  ${DURATION:+-e DURATION="${DURATION}"} \
  ${RAMP:+-e RAMP="${RAMP}"} \
  ${PEAK:+-e PEAK="${PEAK}"} \
  ${BASE_URL:+-e BASE_URL="${BASE_URL}"} \
  ${SKU_COUNT:+-e SKU_COUNT="${SKU_COUNT}"} \
  k6 run --out "${K6_OUTPUT:-experimental-prometheus-rw}" "/scripts/${SCENARIO}.js" "$@"
