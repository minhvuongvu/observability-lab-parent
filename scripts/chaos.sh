#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Injects faults into the network paths between the services and everything
# below them.
#
#   ./scripts/chaos.sh list                    proxies and their active toxics
#   ./scripts/chaos.sh slow <proxy> <ms> [jitter]   add latency
#   ./scripts/chaos.sh down <proxy>            refuse connections outright
#   ./scripts/chaos.sh blackhole <proxy>       accept, then never answer
#   ./scripts/chaos.sh reset-peer <proxy> [ms] RST the connection
#   ./scripts/chaos.sh throttle <proxy> <kbps> cap bandwidth
#   ./scripts/chaos.sh heal <proxy>            remove this proxy's toxics
#   ./scripts/chaos.sh reset                   remove every toxic, enable all
#
#   proxy: postgres | oracle | redis | kafka | minio
#          inventory-http | inventory-grpc
#
# It drives Toxiproxy's HTTP API, which takes effect on the next connection and
# needs no restart of anything. That immediacy is the point: an experiment you
# can undo in one command is an experiment you will actually run.
#
# Every fault here is a NETWORK fault, which is deliberate. A service that
# returns an error is easy to simulate and rarely how production breaks;
# production breaks by getting slow, by half-answering, and by accepting
# connections it never replies to. Those are the ones below.
#
# Scenarios with the expected signal for each, per pillar, are in
# docs/Simulation.md.
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

# Toxiproxy answers JSON. jq is not assumed - it is not installed by default on
# macOS - so every reader here is a fallback chain rather than a dependency.
pretty() {
  if command -v jq >/dev/null 2>&1; then
    jq .
  elif command -v python3 >/dev/null 2>&1; then
    python3 -m json.tool
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
  local json
  json="$(curl -fsS "${API}/proxies")" || return 1
  if command -v python3 >/dev/null 2>&1; then
    printf '%s' "${json}" | python3 -c 'import sys,json; print("\n".join(json.load(sys.stdin)))'
  elif command -v jq >/dev/null 2>&1; then
    printf '%s' "${json}" | jq -r 'keys[]'
  else
    # Last resort: the seed file the proxies were created from. Only wrong if a
    # proxy was added through the API by hand, which nothing here does.
    sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
      "${REPO_ROOT}/infrastructure/toxiproxy/toxiproxy.json"
  fi
}

# The names of the toxics currently active on one proxy. This endpoint returns a
# flat array, so there is nothing to confuse it with - but it uses the same
# readers for consistency.
toxic_names() {
  local json
  json="$(curl -fsS "${API}/proxies/$1/toxics")" || return 1
  if command -v python3 >/dev/null 2>&1; then
    printf '%s' "${json}" | python3 -c 'import sys,json; print("\n".join(t["name"] for t in json.load(sys.stdin)))'
  elif command -v jq >/dev/null 2>&1; then
    printf '%s' "${json}" | jq -r '.[].name'
  else
    printf '%s' "${json}" | grep -o '"name":"[^"]*"' | sed 's/.*:"//;s/"$//'
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

cmd_reset() {
  require_api
  local proxy
  for proxy in $(proxy_names); do
    cmd_heal "${proxy}" >/dev/null
  done
  echo "Every toxic removed; every proxy enabled."
  echo
  echo "Give the services a moment: connection pools and the circuit breaker"
  echo "recover on their own schedule, not on this command's. A breaker in the"
  echo "open state waits out its configured window before it half-opens."
}

case "${1:-help}" in
  list)                shift; cmd_list "$@" ;;
  slow)                shift; cmd_slow "$@" ;;
  down)                shift; cmd_down "$@" ;;
  blackhole)           shift; cmd_blackhole "$@" ;;
  reset-peer)          shift; cmd_reset_peer "$@" ;;
  throttle)            shift; cmd_throttle "$@" ;;
  heal)                shift; cmd_heal "$@" ;;
  reset)               shift; cmd_reset "$@" ;;
  help|-h|--help)      sed -n '2,29p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  *)                   die "unknown command '${1}'. Try: $(basename "$0") help" ;;
esac
