#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Drives the local infrastructure stack.
#
#   ./scripts/infra.sh up        build, start everything, wait until healthy
#   ./scripts/infra.sh build     rebuild the two service images only
#   ./scripts/infra.sh down      stop containers, keep data
#   ./scripts/infra.sh destroy   stop containers and delete all volumes
#   ./scripts/infra.sh restart   down then up
#   ./scripts/infra.sh ps        container status
#   ./scripts/infra.sh health    health of every container, one line each
#   ./scripts/infra.sh logs [service]
#   ./scripts/infra.sh urls      where each UI lives
#   ./scripts/infra.sh <other>   anything else is passed to docker compose
#
# This starts the WHOLE system - infrastructure, both Spring Boot services, the
# observability stack and the simulation tier - on one Docker network. Nothing
# runs outside it. Load and faults are driven separately:
#
#   ./scripts/load.sh  load      sustained high load, from inside the network
#   ./scripts/chaos.sh slow ...  latency, failures and resets on any hop
#   ./scripts/vault.sh status    the secret store: seal state, leases, audit
#
# `up` unseals and seeds Vault before starting anything that needs a secret
# from it. Vault runs a real server, not dev mode, so it boots sealed every
# time - see scripts/vault.sh and docs/Vault.md.
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
# a first boot initialises the database and can take several minutes, and the
# Inventory Service cannot become ready until it has. Raised from 600 when the
# services joined the stack - they now wait for Oracle *and then* run Flyway.
WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-900}"

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
    return
  fi

  # An existing .env is never overwritten - it holds local edits and, in a real
  # setup, credentials. But it also goes stale: every step that adds a component
  # adds keys to .env.example, and a .env written three steps ago is missing all
  # of them. Compose substitutes an empty string for each and carries on, so the
  # symptom is an exporter that cannot authenticate or a port published on :0 -
  # neither of which points at the actual cause.
  local declared present missing count
  declared="$(grep -oE '^[A-Z][A-Z0-9_]*=' "${COMPOSE_DIR}/.env.example" | tr -d '=' | sort -u)"
  present="$(grep -oE '^[A-Z][A-Z0-9_]*=' "${COMPOSE_DIR}/.env" | tr -d '=' | sort -u)"
  missing="$(comm -23 <(echo "${declared}") <(echo "${present}"))"

  [ -n "${missing}" ] || return 0

  count="$(echo "${missing}" | wc -l | tr -d ' ')"
  echo "WARNING: docker/compose/.env is missing ${count} key(s) that .env.example defines:"
  echo "${missing}" | paste -sd' ' - | fold -s -w 74 | sed 's/^/    /'
  echo
  echo "  Compose substitutes an empty string for each, so the stack starts and"
  echo "  misbehaves rather than failing: an exporter with no password, a port"
  echo "  published on :0. Copy the missing blocks across from .env.example, or"
  echo "  delete .env to regenerate it."
  echo
}

# Names the compose files explicitly rather than relying on COMPOSE_FILE from
# .env. Compose splits COMPOSE_FILE on COMPOSE_PATH_SEPARATOR, whose default is
# ':' on macOS and Linux but ';' on Windows - so a colon-separated list turns
# into one nonexistent filename there. Passing -f removes the question entirely.
compose() {
  (cd "${COMPOSE_DIR}" && docker compose \
      -f docker-compose.yml \
      -f docker-compose.platform.yml \
      -f docker-compose.observability.yml \
      -f docker-compose.services.yml \
      -f docker-compose.simulation.yml \
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

# Vault, before anything that needs a secret from it.
#
# This exists because Vault does not run in dev mode. A real Vault boots SEALED
# on every start, so there is no arrangement of depends_on that produces a
# usable secret store on its own - something has to present the unseal keys, and
# that something cannot be a container in the same compose file, because it
# would need the keys the unseal is protecting.
#
# So: start Vault alone, unseal and seed it, then bring up everything else. The
# services declare `vault: condition: service_healthy`, and Vault's healthcheck
# passes only when unsealed - so this ordering is enforced by compose too, not
# merely by the order of the lines here.
bootstrap_vault() {
  echo "Bringing up Vault and unsealing it..."
  compose up -d vault

  # The container reports healthy only once unsealed, which is exactly what we
  # are about to do - so this waits on the process being up, not on health.
  local waited=0
  until docker exec lab-vault vault status >/dev/null 2>&1 \
     || [ "$(docker inspect -f '{{.State.Running}}' lab-vault 2>/dev/null || echo false)" = "true" ]; do
    [ "${waited}" -ge 60 ] && die "Vault did not start within 60s. Check: ./scripts/infra.sh logs vault"
    sleep 2
    waited=$((waited + 2))
  done

  "${SCRIPT_DIR}/vault.sh" bootstrap
  echo
}

cmd_up() {
  echo "Starting the full stack: infrastructure, services, observability, simulation."
  echo "First run pulls several GB of images, compiles both services and initialises"
  echo "Oracle; expect ten minutes or so. Subsequent runs are much faster."
  echo

  bootstrap_vault

  # --build, so a source change is picked up by `up` rather than silently
  # running the previous image. The Docker layer cache makes this cheap when
  # nothing changed, and a stale service image is a debugging session that
  # starts with entirely correct-looking code.
  compose up -d --build --remove-orphans

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
Services
  Order Service         http://${host}:${ORDER_SERVICE_PORT:-8081}
  Inventory Service     http://${host}:${INVENTORY_SERVICE_PORT:-8082}
  Inventory gRPC        ${host}:${INVENTORY_GRPC_PORT:-9082}   (inventory.v1.InventoryService)

Endpoints
  Nginx (edge)          http://${host}:${NGINX_HTTP_PORT:-80}/healthz
  Kong proxy            http://${host}:${KONG_PROXY_PORT:-8000}
  Kong admin API        http://${host}:${KONG_ADMIN_PORT:-8001}
  Kong manager          http://${host}:${KONG_MANAGER_PORT:-8002}
  Keycloak              http://${host}:${KEYCLOAK_PORT:-8080}
  Consul UI             http://${host}:${CONSUL_PORT:-8500}
  Vault UI              http://${host}:${VAULT_PORT:-8200}   (token: jq -r .root_token docker/compose/.vault-keys.json)
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
  Pyroscope             http://${host}:${PYROSCOPE_PORT:-4040}
  Alertmanager          http://${host}:${ALERTMANAGER_PORT:-9093}
  Mailpit (alert email) http://${host}:${MAILPIT_UI_PORT:-8025}
  Alert webhook sink    http://${host}:${ALERT_WEBHOOK_PORT:-8099}
  OTLP (grpc/http)      ${host}:${OTLP_GRPC_PORT:-4317} / ${host}:${OTLP_HTTP_PORT:-4318}
  OpenSearch Dashboards http://${host}:${OPENSEARCH_DASHBOARDS_PORT:-5601}   (profile: search)
  Kibana                http://${host}:${KIBANA_PORT:-5602}   (profile: search)

Data endpoints
  PostgreSQL            ${host}:${POSTGRES_PORT:-5432}
  Oracle                ${host}:${ORACLE_PORT:-1521}/${ORACLE_PDB:-FREEPDB1}
  Kafka (from host)     ${host}:${KAFKA_EXTERNAL_PORT:-29092}
  Redis                 ${host}:${REDIS_HOST_PORT:-6379}
  MinIO API             http://${host}:${MINIO_API_PORT:-9000}

Simulation
  Toxiproxy API         http://${host}:${TOXIPROXY_API_PORT:-8474}/proxies
  Load generator        ./scripts/load.sh  smoke | load | stress | spike | soak
  Fault injection       ./scripts/chaos.sh list | slow | down | reset
EOF
}

cmd_destroy() {
  echo "This deletes every volume in the stack: databases, broker log, object storage."
  echo "It also destroys Vault's storage, which makes the unseal keys in"
  echo "docker/compose/.vault-keys.json meaningless - they open that data and"
  echo "nothing else. They are removed with it, so the next 'up' initialises a"
  echo "fresh Vault instead of finding keys that fit no lock."
  printf "Type 'destroy' to confirm: "
  read -r answer
  [ "${answer}" = "destroy" ] || { echo "Aborted."; return 1; }
  compose down --volumes --remove-orphans
  rm -f "${COMPOSE_DIR}/.vault-keys.json" "${COMPOSE_DIR}"/.env.vault.*
  echo "Stack, volumes and Vault keys removed."
}

main() {
  local cmd="${1:-help}"
  [ $# -gt 0 ] && shift || true

  case "${cmd}" in
    help|-h|--help)
      sed -n '2,29p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      return 0
      ;;
    urls)
      cmd_urls
      return 0
      ;;
  esac

  require_docker
  # Every command below shells out to compose, and compose reads .env. Checking
  # once here means a stale .env is reported whichever way the stack is touched,
  # rather than only on `up`.
  ensure_env

  case "${cmd}" in
    up)      cmd_up ;;
    build)   compose build "$@" order-service inventory-service ;;
    down)    compose down --remove-orphans ;;
    destroy) cmd_destroy ;;
    restart) compose down --remove-orphans && cmd_up ;;
    ps)      compose ps ;;
    health)  cmd_health ;;
    logs)    compose logs -f --tail=200 "$@" ;;
    *)       compose "${cmd}" "$@" ;;
  esac
}

main "$@"
