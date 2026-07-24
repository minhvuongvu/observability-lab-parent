#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Injects faults, at three levels.
#
# NETWORK faults, through Toxiproxy, in the path between the services and
# everything below them:
#
#   ./scripts/chaos.sh list                    proxies and their active toxics
#   ./scripts/chaos.sh slow <proxy> <ms> [jitter]   add latency
#   ./scripts/chaos.sh down <proxy>            refuse connections outright
#   ./scripts/chaos.sh blackhole <proxy>       accept, then never answer
#   ./scripts/chaos.sh reset-peer <proxy> [ms] RST the connection
#   ./scripts/chaos.sh throttle <proxy> <kbps> cap bandwidth
#   ./scripts/chaos.sh heal <proxy>            remove this proxy's toxics
#
#   proxy: postgres | oracle | redis | kafka | minio
#          inventory-http | inventory-grpc
#
# IN-PROCESS faults (step 17), through each service's chaos endpoints - the
# ones a proxy cannot produce because the process has to do them to itself:
#
#   ./scripts/chaos.sh app status [svc]        what each service has injected
#   ./scripts/chaos.sh app latency <svc> <ms> [ratio]
#   ./scripts/chaos.sh app exception <svc> [ratio]
#   ./scripts/chaos.sh app cpu <svc> [threads] [seconds]
#   ./scripts/chaos.sh app memory <svc> <mb>   retain until released
#   ./scripts/chaos.sh app deadlock <svc>      PERMANENT until restart
#   ./scripts/chaos.sh app logs <svc> <lines> [level]
#   ./scripts/chaos.sh app payload <svc> <kb>
#   ./scripts/chaos.sh app traffic <svc> <requests> [concurrency]
#   ./scripts/chaos.sh app db <svc> <seconds> [connections]
#   ./scripts/chaos.sh app cache <svc> <miss|fail>
#   ./scripts/chaos.sh app kafka <svc>         producer-side send failure
#   ./scripts/chaos.sh app dlq <svc>           poison message -> dead letter
#   ./scripts/chaos.sh app breaker <svc> [calls]
#
#   svc: order | inventory
#
# SECRET STORE faults (step 20), through Vault - the slowest-acting of the
# three, because nothing fails until something needs a secret it does not
# already hold:
#
#   ./scripts/chaos.sh vault seal              secrets become unreadable
#   ./scripts/chaos.sh vault unseal            recover
#   ./scripts/chaos.sh vault revoke            drop the dynamic DB credentials
#   ./scripts/chaos.sh vault status            seal state and mounts
#
# And the one that must always work:
#
#   ./scripts/chaos.sh reset                   clear BOTH levels, everywhere
#
# Toxiproxy takes effect on the next connection; the endpoints take effect on
# the next request. Neither needs a restart of anything, which is the point: an
# experiment you can undo in one command is an experiment you will actually run.
#
# The split between the two levels is not arbitrary. A fault injected from
# outside needs no application code and cannot be left switched on in
# production, so anything that can live out there does. What is left in-process
# is what genuinely cannot: a leak, a CPU spike, a deadlock, a poison message.
#
# Scenarios with the expected signal for each, per pillar:
#   docs/Simulation.md         network faults and load
#   docs/FailureSimulation.md  the in-process faults below
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${REPO_ROOT}/docker/compose/.env"

if [ -f "${ENV_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a; . "${ENV_FILE}"; set +a
fi

API="http://${BIND_HOST:-127.0.0.1}:${TOXIPROXY_API_PORT:-8474}"

die() { echo "ERROR: $*" >&2; exit 1; }

require_api() {
  curl -fsS "${API}/version" >/dev/null 2>&1 || die \
"Toxiproxy is not answering on ${API}.
       Start the stack first: ./scripts/infra.sh up"
}

# Resolves a Python that actually runs, printing nothing when there is none.
#
# `command -v python3` is not sufficient, and the way it fails is silent. Windows
# ships an App Execution Alias at WindowsApps/python3 which exists and resolves,
# then refuses to run: it prints "Python was not found; run without arguments to
# install from the Microsoft Store" and exits non-zero. A presence check picks
# that stub over the real interpreter - which on a Windows box is normally called
# `python` - so every JSON reader below fails while `command -v` says it should
# have worked.
#
# Executing the candidate is the only probe that distinguishes the two.
python_bin() {
  local candidate
  for candidate in python3 python; do
    if command -v "${candidate}" >/dev/null 2>&1 \
       && "${candidate}" -c 'import json,sys' >/dev/null 2>&1; then
      printf '%s' "${candidate}"
      return 0
    fi
  done
  return 0
}

# Removes carriage returns from a name list.
#
# Not paranoia. Python's stdout is a text stream, so on Windows `print()`
# translates "\n" into "\r\n" - which means every proxy name parsed below arrives
# with a trailing \r. It is invisible in output and fatal in a URL: the request
# becomes ".../proxies/inventory-grpc\r/toxics" and curl rejects it with
# "URL using bad/illegal format", after which `reset` reports "no such proxy" for
# a proxy that plainly exists.
#
# Applied to every reader rather than only the Python one, so a CRLF arriving
# from any source cannot resurrect this. The repository hit the same class of bug
# once before, in a generator that had to be told newline="\n" explicitly.
strip_cr() { tr -d '\r'; }

# Toxiproxy answers JSON. jq is not assumed - it is not installed by default on
# macOS or on Git Bash - so every reader here is a fallback chain rather than a
# dependency.
pretty() {
  local py
  py="$(python_bin)"
  if command -v jq >/dev/null 2>&1; then
    jq .
  elif [ -n "${py}" ]; then
    "${py}" -m json.tool
  else
    cat
  fi
}

# The names of every configured proxy.
#
# Parsed properly rather than grepped. GET /proxies returns an object keyed by
# proxy name, with each proxy's active toxics nested inside it - and a toxic
# called `blackhole` is indistinguishable from a proxy name to any regex simple
# enough to write inline. That bug's symptom is `reset` failing with "no such
# proxy: blackhole" precisely when something is broken and you most want it to
# work.
proxy_names() {
  local json py
  json="$(curl -fsS "${API}/proxies")" || return 1
  py="$(python_bin)"
  if [ -n "${py}" ]; then
    printf '%s' "${json}" | "${py}" -c 'import sys,json; print("\n".join(json.load(sys.stdin)))' | strip_cr
  elif command -v jq >/dev/null 2>&1; then
    printf '%s' "${json}" | jq -r 'keys[]' | strip_cr
  else
    # Last resort: the seed file the proxies were created from. Only wrong if a
    # proxy was added through the API by hand, which nothing here does.
    sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
      "${REPO_ROOT}/infrastructure/toxiproxy/toxiproxy.json" | strip_cr
  fi
}

# The names of the toxics currently active on one proxy. This endpoint returns a
# flat array, so there is nothing to confuse it with - but it uses the same
# readers for consistency.
toxic_names() {
  local json py
  json="$(curl -fsS "${API}/proxies/$1/toxics")" || return 1
  py="$(python_bin)"
  if [ -n "${py}" ]; then
    printf '%s' "${json}" | "${py}" -c 'import sys,json; print("\n".join(t["name"] for t in json.load(sys.stdin)))' | strip_cr
  elif command -v jq >/dev/null 2>&1; then
    printf '%s' "${json}" | jq -r '.[].name' | strip_cr
  else
    printf '%s' "${json}" | grep -o '"name":"[^"]*"' | sed 's/.*:"//;s/"$//' | strip_cr
  fi
}

# add_toxic <proxy> <name> <type> <stream> <json-attributes>
#
# The name is derived from the type rather than left to Toxiproxy's default, so
# `heal` can remove a specific fault and re-applying the same kind of fault
# replaces it instead of stacking two. Two latency toxics on one proxy add up,
# which is a confusing way to discover you ran the command twice.
add_toxic() {
  local proxy="$1" name="$2" type="$3" stream="$4" attrs="$5"
  # Idempotent: remove any previous toxic of this name first, ignoring a 404.
  curl -fsS -X DELETE "${API}/proxies/${proxy}/toxics/${name}" >/dev/null 2>&1 || true
  curl -fsS -X POST "${API}/proxies/${proxy}/toxics" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"${name}\",\"type\":\"${type}\",\"stream\":\"${stream}\",\"toxicity\":1.0,\"attributes\":${attrs}}" \
    >/dev/null || die "could not add ${type} to '${proxy}'. Does that proxy exist? Try: $(basename "$0") list"
}

cmd_list() {
  require_api
  curl -fsS "${API}/proxies" | pretty
}

cmd_slow() {
  local proxy="${1:-}" ms="${2:-}" jitter="${3:-0}"
  [ -n "${proxy}" ] && [ -n "${ms}" ] || die "usage: $(basename "$0") slow <proxy> <ms> [jitter-ms]"
  require_api
  # Downstream: the delay is applied to bytes coming back from the dependency,
  # which is what a slow query or a loaded broker actually looks like. Upstream
  # would delay the request on its way out, which is a client-side problem
  # wearing a server-side costume.
  add_toxic "${proxy}" latency_downstream latency downstream \
    "{\"latency\":${ms},\"jitter\":${jitter}}"
  echo "'${proxy}' now responds ${ms}ms slower (jitter ${jitter}ms)."
  echo
  echo "Expect, in order: client timeouts, then connection-pool exhaustion as"
  echo "callers hold connections longer, then the circuit breaker's SLOW-CALL"
  echo "threshold - not its error threshold. Watch the difference in Grafana."
}

cmd_down() {
  local proxy="${1:-}"
  [ -n "${proxy}" ] || die "usage: $(basename "$0") down <proxy>"
  require_api
  # Disabling the proxy closes existing connections and refuses new ones, which
  # is the connection-refused case: fast, unambiguous, and the *easy* failure.
  curl -fsS -X POST "${API}/proxies/${proxy}" \
    -H 'Content-Type: application/json' -d '{"enabled":false}' >/dev/null \
    || die "no such proxy: '${proxy}'"
  echo "'${proxy}' is refusing connections."
  echo
  echo "This is the failure that fails FAST. Compare it with 'blackhole', which"
  echo "is the same outage from the dependency's point of view and far worse for"
  echo "the caller - because nothing tells it anything is wrong."
}

cmd_blackhole() {
  local proxy="${1:-}"
  [ -n "${proxy}" ] || die "usage: $(basename "$0") blackhole <proxy>"
  require_api
  # timeout with 0 means: accept the connection and never respond, ever. No RST,
  # no FIN, no error - the caller sits there until its own timeout fires, and if
  # it has no timeout it sits there forever.
  add_toxic "${proxy}" blackhole timeout downstream '{"timeout":0}'
  echo "'${proxy}' now accepts connections and never answers."
  echo
  echo "The nastiest failure in the set, and the one worth studying. A TCP health"
  echo "check on this dependency still PASSES - the port is open. Only a caller"
  echo "with a timeout ever finds out. Anything without one leaks a thread per"
  echo "request until the pool is gone."
}

cmd_reset_peer() {
  local proxy="${1:-}" ms="${2:-0}"
  [ -n "${proxy}" ] || die "usage: $(basename "$0") reset-peer <proxy> [after-ms]"
  require_api
  add_toxic "${proxy}" reset_peer reset_peer downstream "{\"timeout\":${ms}}"
  echo "'${proxy}' will RST its connections after ${ms}ms."
  echo
  echo "The mid-flight failure: the request was sent and may well have been"
  echo "processed. This is where retry policies get interesting, because a retry"
  echo "of a non-idempotent write can duplicate it."
}

cmd_throttle() {
  local proxy="${1:-}" kbps="${2:-}"
  [ -n "${proxy}" ] && [ -n "${kbps}" ] || die "usage: $(basename "$0") throttle <proxy> <kb/s>"
  require_api
  add_toxic "${proxy}" bandwidth bandwidth downstream "{\"rate\":${kbps}}"
  echo "'${proxy}' is capped at ${kbps} KB/s downstream."
  echo
  echo "Small responses stay fast while large ones crawl, so latency correlates"
  echo "with payload size rather than with load - which is the signature of a"
  echo "saturated link and looks nothing like a slow query."
}

cmd_heal() {
  local proxy="${1:-}"
  [ -n "${proxy}" ] || die "usage: $(basename "$0") heal <proxy>"
  require_api
  curl -fsS -X POST "${API}/proxies/${proxy}" \
    -H 'Content-Type: application/json' -d '{"enabled":true}' >/dev/null \
    || die "no such proxy: '${proxy}'"

  local name
  for name in $(toxic_names "${proxy}"); do
    curl -fsS -X DELETE "${API}/proxies/${proxy}/toxics/${name}" >/dev/null 2>&1 || true
  done
  echo "'${proxy}' is healthy again."
}

# ============================================================================
# In-process faults (step 17)
#
# These call each service's own /api/v1/chaos endpoints, which exist only under
# the local and dev profiles and require the ADMIN role. Everything below is a
# fault the process inflicts on itself and that no proxy could produce.
# ============================================================================

ORDER_URL="http://${BIND_HOST:-127.0.0.1}:${ORDER_SERVICE_PORT:-8081}"
INVENTORY_URL="http://${BIND_HOST:-127.0.0.1}:${INVENTORY_SERVICE_PORT:-8082}"

# Resolved once per invocation. The endpoints require ADMIN, which `manager`
# has and `alice` does not.
CHAOS_TOKEN=""
chaos_token() {
  if [ -z "${CHAOS_TOKEN}" ]; then
    CHAOS_TOKEN="$("${SCRIPT_DIR}/token.sh" manager 2>/dev/null)" \
      || die "could not obtain an ADMIN token. Is Keycloak up? ./scripts/infra.sh health"
  fi
  printf '%s' "${CHAOS_TOKEN}"
}

# service_url <order|inventory>
service_url() {
  case "${1:-}" in
    order|order-service)         printf '%s' "${ORDER_URL}" ;;
    inventory|inventory-service) printf '%s' "${INVENTORY_URL}" ;;
    *) die "unknown service '${1:-}'. Use 'order' or 'inventory'." ;;
  esac
}

# app_call <service> <METHOD> <path> [json-body]
app_call() {
  local svc="$1" method="$2" path="$3" body="${4:-}"
  local url; url="$(service_url "${svc}")"
  local args=(-fsS -X "${method}" "${url}${path}"
              -H "Authorization: Bearer $(chaos_token)")
  if [ -n "${body}" ]; then
    args+=(-H 'Content-Type: application/json' -d "${body}")
  fi
  curl "${args[@]}" || die \
"${method} ${path} on '${svc}' failed.
       404 means the service is running a profile without the chaos endpoints
       (they exist only under local and dev). 403 means the token lacks ADMIN."
}

cmd_app() {
  local action="${1:-status}"
  [ $# -gt 0 ] && shift || true

  case "${action}" in
    status)
      local svc
      for svc in ${1:-order inventory}; do
        echo "=== ${svc} ==="
        app_call "${svc}" GET /api/v1/chaos | pretty
      done
      ;;

    latency)
      local svc="${1:?usage: app latency <svc> <ms> [ratio]}" ms="${2:?missing ms}" ratio="${3:-1.0}"
      app_call "${svc}" POST /api/v1/chaos/latency \
        "{\"delayMs\":${ms},\"ratio\":${ratio},\"ttlSeconds\":0}" | pretty
      echo
      echo "Every API request on '${svc}' now sleeps ${ms}ms on its request thread."
      echo "Watch tomcat_threads_busy climb: the thread is held, not parked."
      ;;

    exception)
      local svc="${1:?usage: app exception <svc> [ratio]}" ratio="${2:-1.0}"
      app_call "${svc}" POST /api/v1/chaos/exception \
        "{\"ratio\":${ratio},\"ttlSeconds\":0}" | pretty
      ;;

    cpu)
      local svc="${1:?usage: app cpu <svc> [threads] [seconds]}" threads="${2:-4}" seconds="${3:-60}"
      app_call "${svc}" POST /api/v1/chaos/cpu \
        "{\"threads\":${threads},\"durationSeconds\":${seconds}}" | pretty
      echo
      echo "Heap stays flat and process_cpu_usage climbs. That pairing is the"
      echo "signature - compare it with 'app memory', which does the opposite."
      ;;

    memory)
      local svc="${1:?usage: app memory <svc> <mb>}" mb="${2:?missing mb}"
      app_call "${svc}" POST /api/v1/chaos/memory-leak "{\"megabytes\":${mb}}" | pretty
      echo
      echo "Released with: $(basename "$0") app release-memory ${svc}"
      ;;

    release-memory)
      local svc="${1:?usage: app release-memory <svc>}"
      app_call "${svc}" DELETE /api/v1/chaos/memory-leak | pretty
      ;;

    deadlock)
      local svc="${1:?usage: app deadlock <svc>}"
      echo "This is PERMANENT. Two threads are lost until the container restarts."
      app_call "${svc}" POST /api/v1/chaos/deadlock | pretty
      echo
      echo "Nothing in metrics or traces will say 'deadlock'. The JVM knows:"
      echo "  docker exec lab-${svc}-service jcmd 1 Thread.print | grep -A5 deadlock"
      ;;

    logs)
      local svc="${1:?usage: app logs <svc> <lines> [level]}" lines="${2:?missing lines}" level="${3:-INFO}"
      app_call "${svc}" POST /api/v1/chaos/log-burst \
        "{\"lines\":${lines},\"level\":\"${level}\",\"paddingBytes\":256}" | pretty
      ;;

    payload)
      local svc="${1:?usage: app payload <svc> <kb>}" kb="${2:?missing kb}"
      local url; url="$(service_url "${svc}")"
      echo "Direct to the service:"
      curl -fsS -o /dev/null -w "  %{size_download} bytes in %{time_total}s\n" \
        -H "Authorization: Bearer $(chaos_token)" \
        "${url}/api/v1/chaos/large-payload?sizeKb=${kb}"
      echo "Through the edge, where Kong's 1MB request-size-limiting plugin has an opinion:"
      curl -s -o /dev/null -w "  HTTP %{http_code}, %{size_download} bytes in %{time_total}s\n" \
        -H "Authorization: Bearer $(chaos_token)" \
        "http://${BIND_HOST:-127.0.0.1}:${NGINX_HTTP_PORT:-80}/api/v1/chaos/large-payload?sizeKb=${kb}" \
        || true
      ;;

    traffic)
      local svc="${1:?usage: app traffic <svc> <requests> [concurrency]}" reqs="${2:?missing requests}" conc="${3:-50}"
      app_call "${svc}" POST /api/v1/chaos/traffic \
        "{\"requests\":${reqs},\"concurrency\":${conc},\"workMillis\":250}" | pretty
      ;;

    db)
      local svc="${1:?usage: app db <svc> <seconds> [connections]}" secs="${2:?missing seconds}" conns="${3:-12}"
      app_call "${svc}" POST /api/v1/chaos/db-slow \
        "{\"seconds\":${secs},\"connections\":${conns}}" | pretty
      echo
      echo "With connections >= the pool size this exhausts it, and endpoints"
      echo "with nothing to do with the database start failing. That indirection"
      echo "is the lesson: the endpoint that breaks is rarely the one at fault."
      ;;

    cache)
      local svc="${1:?usage: app cache <svc> <miss|fail>}" mode="${2:-miss}"
      app_call "${svc}" POST /api/v1/chaos/cache \
        "{\"mode\":\"${mode}\",\"ratio\":1.0,\"ttlSeconds\":0}" | pretty
      echo
      echo "Note what stays green: Redis is healthy, readiness is UP, and the"
      echo "database quietly takes the full uncached load."
      ;;

    kafka)
      local svc="${1:?usage: app kafka <svc>}"
      app_call "${svc}" POST /api/v1/chaos/kafka | pretty
      ;;

    dlq)
      local svc="${1:?usage: app dlq <svc>}"
      app_call "${svc}" POST /api/v1/chaos/dead-letter | pretty
      echo
      echo "Watch the retries, then one record on dead-letter-topic:"
      echo "  ./scripts/infra.sh logs ${svc}-service | grep -i 'retry\|dead'"
      ;;

    breaker)
      local svc="${1:-order}" calls="${2:-30}"
      app_call "${svc}" POST /api/v1/chaos/circuit-breaker "{\"calls\":${calls}}" | pretty
      ;;

    reset)
      local svc
      for svc in ${1:-order inventory}; do
        echo "=== ${svc} ==="
        app_call "${svc}" DELETE /api/v1/chaos | pretty
      done
      ;;

    *)
      die "unknown app command '${action}'. Try: $(basename "$0") help"
      ;;
  esac
}

cmd_reset() {
  require_api
  local proxy
  for proxy in $(proxy_names); do
    cmd_heal "${proxy}" >/dev/null
  done
  echo "Network: every toxic removed, every proxy enabled."

  # Both levels, always. A reset that cleared only the proxies would leave a
  # latency toggle running inside a service, and the next person to look at a
  # dashboard would be investigating a fault somebody injected on purpose and
  # forgot - which is the exact failure mode this command exists to prevent.
  #
  # Best effort: the services may legitimately be down, and a reset that
  # refused to clear the proxies because a service was unreachable would be
  # useless precisely when it is needed.
  local svc cleared
  for svc in order inventory; do
    if cleared="$(app_call "${svc}" DELETE /api/v1/chaos 2>/dev/null)"; then
      echo "In-process (${svc}): $(printf '%s' "${cleared}" | tr -d '\n' | sed 's/.*"togglesCleared"://;s/,.*//') toggle(s) cleared."
      case "${cleared}" in
        *'"clean":false'*)
          echo "  WARNING: this service has deadlocked threads, which cannot be cleared."
          echo "           Restart it: docker restart lab-${svc}-service"
          ;;
      esac
    else
      echo "In-process (${svc}): unreachable, nothing cleared."
    fi
  done

  # A sealed Vault is a fault like any other, and the one most likely to be
  # left behind: it breaks nothing visibly, so there is no symptom to remind
  # anyone it is still injected. The next person to restart a service would
  # find it unable to start, hours later, for no apparent reason.
  if [ "$("${SCRIPT_DIR}/vault.sh" status 2>/dev/null | awk '/^Sealed/{print $2}')" = "true" ]; then
    echo "Vault: sealed, unsealing."
    "${SCRIPT_DIR}/vault.sh" unseal >/dev/null 2>&1 \
      && echo "Vault: unsealed." \
      || echo "Vault: could NOT be unsealed - run ./scripts/vault.sh unseal by hand."
  else
    echo "Vault: unsealed, nothing to do."
  fi

  echo
  echo "Give the services a moment: connection pools and the circuit breaker"
  echo "recover on their own schedule, not on this command's. A breaker in the"
  echo "open state waits out its configured window before it half-opens."
}

# -----------------------------------------------------------------------------
# Vault faults (step 20).
#
# A third level, and the one with the longest fuse. Sealing Vault breaks nothing
# immediately: every service keeps serving from the connections and secrets it
# already holds, and fails only at its next renewal or its next new connection.
# That delay - minutes, not seconds - is the lesson. The cause and the symptom
# are far enough apart that the symptom looks unrelated to anything anyone did.
#
# Delegated to vault.sh rather than reimplemented, so there is one place that
# knows how to talk to Vault.
# -----------------------------------------------------------------------------
cmd_vault() {
  local action="${1:-status}"
  local vault_sh
  vault_sh="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/vault.sh"

  case "${action}" in
    seal)
      "${vault_sh}" seal
      echo
      echo "Expected: no immediate change. Watch for the delayed failure -"
      echo "  vault_core_unsealed goes to 0 in Prometheus, immediately"
      echo "  VaultSealed fires after 1m"
      echo "  the services degrade only at their next lease renewal"
      ;;
    unseal)
      "${vault_sh}" unseal
      ;;
    revoke)
      "${vault_sh}" revoke
      echo
      echo "Expected: the Order Service keeps working on its open connections."
      echo "Force it to open a new one and watch it fail:"
      echo "  ./scripts/chaos.sh app db order 30 20"
      ;;
    status)
      "${vault_sh}" status
      ;;
    *)
      die "unknown vault action '${action}'. Try: seal | unseal | revoke | status"
      ;;
  esac
}

case "${1:-help}" in
  list)                shift; cmd_list "$@" ;;
  slow)                shift; cmd_slow "$@" ;;
  down)                shift; cmd_down "$@" ;;
  blackhole)           shift; cmd_blackhole "$@" ;;
  reset-peer)          shift; cmd_reset_peer "$@" ;;
  throttle)            shift; cmd_throttle "$@" ;;
  heal)                shift; cmd_heal "$@" ;;
  app)                 shift; cmd_app "$@" ;;
  vault)               shift; cmd_vault "$@" ;;
  reset)               shift; cmd_reset "$@" ;;
  help|-h|--help)      sed -n '2,63p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  *)                   die "unknown command '${1}'. Try: $(basename "$0") help" ;;
esac
