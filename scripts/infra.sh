#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Drives the local infrastructure stack.
#
#   ./scripts/infra.sh up        start everything and wait until healthy
#   ./scripts/infra.sh down      stop containers, keep data
#   ./scripts/infra.sh destroy   stop containers and delete all volumes
#   ./scripts/infra.sh restart   down then up
#   ./scripts/infra.sh ps        container status
#   ./scripts/infra.sh health    health of every container, one line each
#   ./scripts/infra.sh logs [service]
#   ./scripts/infra.sh urls      where each UI lives
#   ./scripts/infra.sh <other>   anything else is passed to docker compose
#
# The compose files are combined through COMPOSE_FILE in docker/compose/.env,
# which this script creates from .env.example on first run.
# ---------------------------------------------------------------------------
set -euo pipefail

# Git Bash rewrites arguments that look like absolute POSIX paths into Windows
# paths before passing them to a program, which breaks any container path given
# to `docker compose exec`. Disabled here; ignored on every other platform.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$(cd "${SCRIPT_DIR}/../docker/compose" && pwd)"

# Containers that legitimately run to completion and exit.
ONESHOT_SERVICES="kafka-init minio-init consul-init"

# How long to wait for the stack to become healthy. Oracle dominates this:
# a first boot initialises the database and can take several minutes.
WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-600}"

die() { echo "ERROR: $*" >&2; exit 1; }

require_docker() {
  command -v docker >/dev/null 2>&1 || die "Docker is not installed."
  docker compose version >/dev/null 2>&1 || die "The 'docker compose' plugin is not available."
  docker info >/dev/null 2>&1 || die "The Docker daemon is not running. Start Docker and retry."
}

ensure_env() {
  if [ ! -f "${COMPOSE_DIR}/.env" ]; then
    cp "${COMPOSE_DIR}/.env.example" "${COMPOSE_DIR}/.env"
    echo "Created docker/compose/.env from .env.example."
    echo "It carries local-only placeholder credentials; review it before changing BIND_HOST."
    echo
  fi
}

# Names the compose files explicitly rather than relying on COMPOSE_FILE from
# .env. Compose splits COMPOSE_FILE on COMPOSE_PATH_SEPARATOR, whose default is
# ':' on macOS and Linux but ';' on Windows - so a colon-separated list turns
# into one nonexistent filename there. Passing -f removes the question entirely.
compose() {
  (cd "${COMPOSE_DIR}" && docker compose \
      -f docker-compose.yml \
      -f docker-compose.platform.yml \
      "$@")
}

# Prints "name|state|health|exitcode" for every container in the project.
container_status() {
  local ids id
  ids="$(compose ps -aq 2>/dev/null || true)"
  [ -n "${ids}" ] || return 0
  for id in ${ids}; do
    docker inspect --format \
      '{{slice .Name 1}}|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{.State.ExitCode}}' \
      "${id}" 2>/dev/null || true
  done
}

is_oneshot() {
  case " ${ONESHOT_SERVICES} " in *" ${1#lab-} "*) return 0 ;; *) return 1 ;; esac
}

# Returns 0 once every long-running container is healthy (or running with no
# healthcheck) and every one-shot container has exited cleanly.
stack_is_ready() {
  local name state health exitcode pending=0
  while IFS='|' read -r name state health exitcode; do
    [ -n "${name}" ] || continue
    if is_oneshot "${name}"; then
      # A provisioning container must not merely stop, it must succeed.
      # Accepting any exit code would let a failed bucket or topic setup pass
      # for a healthy stack, and the failure would only surface much later.
      { [ "${state}" = "exited" ] && [ "${exitcode}" = "0" ]; } || pending=1
    else
      case "${health}" in
        healthy)  ;;
        none)     [ "${state}" = "running" ] || pending=1 ;;
        *)        pending=1 ;;
      esac
    fi
  done <<EOF
$(container_status)
EOF
  return "${pending}"
}

cmd_health() {
  printf "%-20s %-12s %s\n" "CONTAINER" "STATE" "HEALTH"
  printf "%-20s %-12s %s\n" "--------------------" "------------" "----------"
  while IFS='|' read -r name state health exitcode; do
    [ -n "${name}" ] || continue
    # A one-shot container has no health; what matters is how it exited.
    [ "${state}" = "exited" ] && health="exit ${exitcode}"
    printf "%-20s %-12s %s\n" "${name}" "${state}" "${health}"
  done <<EOF
$(container_status)
EOF
}

cmd_up() {
  ensure_env
  echo "Starting the infrastructure stack."
  echo "First run pulls several GB of images and initialises Oracle; expect a few minutes."
  echo
  compose up -d --remove-orphans

  echo
  echo "Waiting for containers to become healthy (timeout ${WAIT_TIMEOUT_SECONDS}s)..."
  local waited=0
  while [ "${waited}" -lt "${WAIT_TIMEOUT_SECONDS}" ]; do
    if stack_is_ready; then
      echo
      echo "Stack is ready."
      echo
      cmd_health
      echo
      cmd_urls
      return 0
    fi
    sleep 5
    waited=$((waited + 5))
    printf "\r  %ss elapsed" "${waited}"
  done

  echo
  echo "Timed out after ${WAIT_TIMEOUT_SECONDS}s. Current state:"
  echo
  cmd_health
  echo
  echo "Inspect a specific container with: ./scripts/infra.sh logs <service>"
  return 1
}

cmd_urls() {
  # Reads the effective values so the list reflects .env rather than defaults.
  local env_file="${COMPOSE_DIR}/.env"
  # shellcheck disable=SC1090
  [ -f "${env_file}" ] && set -a && . "${env_file}" && set +a

  local host="${BIND_HOST:-127.0.0.1}"
  cat <<EOF
Endpoints
  Nginx (edge)          http://${host}:${NGINX_HTTP_PORT:-80}/healthz
  Kong proxy            http://${host}:${KONG_PROXY_PORT:-8000}
  Kong admin API        http://${host}:${KONG_ADMIN_PORT:-8001}
  Kong manager          http://${host}:${KONG_MANAGER_PORT:-8002}
  Keycloak              http://${host}:${KEYCLOAK_PORT:-8080}
  Consul UI             http://${host}:${CONSUL_PORT:-8500}
  Kafka UI              http://${host}:${KAFKA_UI_PORT:-8090}
  MinIO console         http://${host}:${MINIO_CONSOLE_PORT:-9001}

Observability
  Grafana               http://${host}:${GRAFANA_PORT:-3000}
  Prometheus            http://${host}:${PROMETHEUS_PORT:-9090}
  VictoriaMetrics       http://${host}:${VICTORIAMETRICS_PORT:-8428}
  Loki API              http://${host}:${LOKI_PORT:-3100}
  Tempo API             http://${host}:${TEMPO_PORT:-3200}
  Jaeger UI             http://${host}:${JAEGER_UI_PORT:-16686}
  Zipkin UI             http://${host}:${ZIPKIN_PORT:-9411}
  OTLP (grpc/http)      ${host}:${OTLP_GRPC_PORT:-4317} / ${host}:${OTLP_HTTP_PORT:-4318}
  OpenSearch Dashboards http://${host}:${OPENSEARCH_DASHBOARDS_PORT:-5601}   (profile: search)
  Kibana                http://${host}:${KIBANA_PORT:-5602}   (profile: search)

Data endpoints
  PostgreSQL            ${host}:${POSTGRES_PORT:-5432}
  Oracle                ${host}:${ORACLE_PORT:-1521}/${ORACLE_PDB:-FREEPDB1}
  Kafka (from host)     ${host}:${KAFKA_EXTERNAL_PORT:-29092}
  Redis                 ${host}:${REDIS_PORT:-6379}
  MinIO API             http://${host}:${MINIO_API_PORT:-9000}
EOF
}

cmd_destroy() {
  echo "This deletes every volume in the stack: databases, broker log, object storage."
  printf "Type 'destroy' to confirm: "
  read -r answer
  [ "${answer}" = "destroy" ] || { echo "Aborted."; return 1; }
  compose down --volumes --remove-orphans
  echo "Stack and volumes removed."
}

main() {
  local cmd="${1:-help}"
  [ $# -gt 0 ] && shift || true

  case "${cmd}" in
    help|-h|--help)
      sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      return 0
      ;;
    urls)
      cmd_urls
      return 0
      ;;
  esac

  require_docker

  case "${cmd}" in
    up)      cmd_up ;;
    down)    compose down --remove-orphans ;;
    destroy) cmd_destroy ;;
    restart) compose down --remove-orphans && cmd_up ;;
    ps)      compose ps ;;
    health)  cmd_health ;;
    logs)    ensure_env; compose logs -f --tail=200 "$@" ;;
    *)       ensure_env; compose "${cmd}" "$@" ;;
  esac
}

main "$@"
