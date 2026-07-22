#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Runs one production-failure scenario end to end.
#
#   ./scripts/scenario.sh list                  every scenario, one line each
#   ./scripts/scenario.sh <name>                run it
#   ./scripts/scenario.sh <name> --under-load   run it while k6 offers traffic
#   ./scripts/scenario.sh all                   run every scenario in sequence
#
# Each run does the same four things, in the same order:
#
#   1. Inject   the fault, by exactly the command the guide documents
#   2. Hold     it for a stated duration, so the signals have time to move
#   3. Heal     it, always, even if the run is interrupted
#   4. Report   what to look at, and where
#
# Why this exists rather than a list of curl commands in a document: a scenario
# that has to be assembled by hand is a scenario nobody runs twice, and one that
# is run twice slightly differently is not evidence of anything.
#
# --under-load matters more than it looks. A fault injected into an idle system
# tells you what the mechanism does; a fault injected into a saturated one tells
# you what it does when it matters, and they are frequently not the same answer.
# docs/Simulation.md scenario 7 is the worked example.
#
# The expected signals for every scenario are written down BEFORE the run, in
# docs/FailureSimulation.md. Read them first. A scenario whose expectations were
# decided after seeing the output confirms nothing.
# ---------------------------------------------------------------------------
set -euo pipefail

export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${REPO_ROOT}/docker/compose/.env"

if [ -f "${ENV_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a; . "${ENV_FILE}"; set +a
fi

HOST="${BIND_HOST:-127.0.0.1}"
GRAFANA="http://${HOST}:${GRAFANA_PORT:-3000}"
PROMETHEUS="http://${HOST}:${PROMETHEUS_PORT:-9090}"
CHAOS="${SCRIPT_DIR}/chaos.sh"

HOLD_SECONDS="${HOLD_SECONDS:-90}"
UNDER_LOAD=0
LOAD_PID=""

die() { echo "ERROR: $*" >&2; exit 1; }

# --------------------------------------------------------------------------
# The catalogue.
#
# name|what it breaks|the guide section
# --------------------------------------------------------------------------
SCENARIOS="
slow-request|Every API call sleeps on its request thread|1
db-exhaustion|Sleeping queries hold every pool connection|2
cache-silent|Cache misses everything while Redis stays healthy|3
cpu-spike|Cores burn; heap stays flat|4
memory-leak|Live objects accumulate and are never released|5
deadlock|Two threads lock in opposite order, permanently|6
exception|A proportion of API calls fail before their handler|7
dead-letter|A poison message exhausts retries and is dead-lettered|8
kafka-producer|A send the broker refuses, with a healthy cluster|9
log-burst|The log pipeline takes more than it can carry|10
large-payload|A response big enough for the edge to have opinions|11
internal-traffic|Internal pools saturate with no network in the path|12
circuit-breaker|Real calls through a genuinely broken dependency|13
"

cmd_list() {
  printf "%-18s %-58s %s\n" "SCENARIO" "WHAT IT BREAKS" "GUIDE"
  printf "%-18s %-58s %s\n" "------------------" \
    "----------------------------------------------------------" "-----"
  echo "${SCENARIOS}" | while IFS='|' read -r name what section; do
    [ -n "${name}" ] || continue
    printf "%-18s %-58s §%s\n" "${name}" "${what}" "${section}"
  done
  echo
  echo "Expected signals for each: docs/FailureSimulation.md"
}

# --------------------------------------------------------------------------
# Housekeeping
# --------------------------------------------------------------------------

require_stack() {
  docker inspect --format '{{.State.Status}}' lab-order-service 2>/dev/null \
    | grep -q running || die \
"lab-order-service is not running. Start the stack first: ./scripts/infra.sh up"
}

# Always heal. A scenario that leaves a fault behind because somebody pressed
# Ctrl-C is precisely the failure this whole script exists to make impossible.
cleanup() {
  local code=$?
  echo
  echo "--- healing ---"
  if [ -n "${LOAD_PID}" ] && kill -0 "${LOAD_PID}" 2>/dev/null; then
    kill "${LOAD_PID}" 2>/dev/null || true
    wait "${LOAD_PID}" 2>/dev/null || true
    echo "load generator stopped"
  fi
  "${CHAOS}" reset 2>&1 | sed 's/^/  /' || true
  exit "${code}"
}

start_load_if_requested() {
  [ "${UNDER_LOAD}" -eq 1 ] || return 0
  echo "--- starting background load (k6) ---"
  DURATION="${HOLD_SECONDS}s" RAMP=10s "${SCRIPT_DIR}/load.sh" load >/dev/null 2>&1 &
  LOAD_PID=$!
  # Let the ramp establish a baseline before the fault lands, or the fault's
  # effect and the ramp's are indistinguishable in the graphs.
  sleep 20
  echo "load running (pid ${LOAD_PID})"
}

hold() {
  local seconds="${1:-${HOLD_SECONDS}}"
  echo
  echo "--- holding for ${seconds}s (Ctrl-C heals and exits) ---"
  local elapsed=0
  while [ "${elapsed}" -lt "${seconds}" ]; do
    sleep 5
    elapsed=$((elapsed + 5))
    printf "\r  %ss/%ss" "${elapsed}" "${seconds}"
  done
  echo
}

banner() {
  echo
  echo "==========================================================================="
  echo "  SCENARIO: $1"
  echo "  $2"
  echo "==========================================================================="
  echo "Expected signals are in docs/FailureSimulation.md §$3 - read them first."
  echo
}

# report <blank-separated list of "label=query"> ...
watch_here() {
  echo
  echo "--- where to look ---"
  echo "  Grafana     ${GRAFANA}"
  echo "  Prometheus  ${PROMETHEUS}/graph"
  local line
  for line in "$@"; do
    echo "  ${line}"
  done
  echo
  echo "  Was it us?  lab_chaos_active_faults > 0 marks every window a fault was injected."
}

# --------------------------------------------------------------------------
# Scenarios
# --------------------------------------------------------------------------

scenario_slow_request() {
  banner "slow-request" "Every API call sleeps 800ms on its request thread" 1
  "${CHAOS}" app latency order 800 >/dev/null
  echo "injected: 800ms on every order-service API call"
  hold
  watch_here \
    "PromQL      histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{service=\"order-service\"}[1m])))" \
    "PromQL      tomcat_threads_busy_threads{service=\"order-service\"}" \
    "Expect      p95 above 800ms, busy threads climbing, health still UP"
}

scenario_db_exhaustion() {
  banner "db-exhaustion" "Sleeping queries hold every connection in the pool" 2
  "${CHAOS}" app db order 120 12 >/dev/null
  echo "injected: 12 connections held for 120s (the pool has 10)"
  hold
  watch_here \
    "PromQL      hikaricp_connections_pending{service=\"order-service\"}" \
    "PromQL      rate(http_server_requests_seconds_count{service=\"order-service\",outcome=\"SERVER_ERROR\"}[1m])" \
    "Expect      pending climbs first, errors follow ~3s later (connection-timeout)" \
    "Note        postgres-exporter stays perfectly healthy: the database is fine"
}

scenario_cache_silent() {
  banner "cache-silent" "Every cache read misses while Redis stays healthy" 3
  "${CHAOS}" app cache order miss >/dev/null
  echo "injected: cache miss mode on order-service"
  hold
  watch_here \
    "PromQL      rate(hikaricp_connections_acquire_seconds_count{service=\"order-service\"}[1m])" \
    "PromQL      up{job=\"redis\"}" \
    "Expect      database load up, Redis up==1, readiness UP, nothing alerting" \
    "Lesson      this is the cache failure no health check can see"
}

scenario_cpu_spike() {
  banner "cpu-spike" "Four threads burn CPU; the heap does not move" 4
  "${CHAOS}" app cpu order 4 "${HOLD_SECONDS}" >/dev/null
  echo "injected: 4 burn threads for ${HOLD_SECONDS}s"
  hold
  watch_here \
    "PromQL      process_cpu_usage{service=\"order-service\"}" \
    "PromQL      jvm_memory_used_bytes{service=\"order-service\",area=\"heap\"}" \
    "Pyroscope   ${HOST}:${PYROSCOPE_PORT:-4040} - the burn loop dominates the flame graph" \
    "Expect      CPU up, heap flat. Compare with memory-leak, which is the mirror image"
}

scenario_memory_leak() {
  banner "memory-leak" "Live objects accumulate and are never released" 5
  "${CHAOS}" app memory order 256 >/dev/null
  echo "injected: 256MB retained (the container limit is ${ORDER_SERVICE_MEMORY:-768M})"
  hold
  watch_here \
    "PromQL      jvm_memory_used_bytes{service=\"order-service\",area=\"heap\"}" \
    "PromQL      rate(jvm_gc_pause_seconds_sum{service=\"order-service\"}[1m])" \
    "Pyroscope   alloc_live names the allocation site; alloc alone would not" \
    "Expect      the post-GC floor rises and stays. A rising floor IS the leak"
}

scenario_deadlock() {
  banner "deadlock" "Two threads take two locks in opposite order. Permanent." 6
  echo "This one cannot be healed. The threads are lost until the container restarts."
  "${CHAOS}" app deadlock order >/dev/null
  echo "injected: two deadlocked threads on order-service"
  hold 30
  watch_here \
    "Command     docker exec lab-order-service jcmd 1 Thread.print | grep -A20 -i deadlock" \
    "Expect      NOTHING in metrics, logs or traces says 'deadlock'" \
    "Lesson      the JVM knows and will tell anything that asks. No signal volunteers it" \
    "Remedy      docker restart lab-order-service"
}

scenario_exception() {
  banner "exception" "One API call in four fails before reaching its handler" 7
  "${CHAOS}" app exception order 0.25 >/dev/null
  echo "injected: 25% failure rate on order-service"
  hold
  watch_here \
    "PromQL      sum(rate(http_server_requests_seconds_count{service=\"order-service\",outcome=\"SERVER_ERROR\"}[1m])) / sum(rate(http_server_requests_seconds_count{service=\"order-service\"}[1m]))" \
    "LogQL       {service=\"order-service\"} |= \"ChaosInjectedException\"" \
    "Expect      ~25% error rate, HighErrorRate alert after its for: window" \
    "Note        a distinct exception type is what separates this from a real failure"
}

scenario_dead_letter() {
  banner "dead-letter" "A poison message exhausts its retries and is dead-lettered" 8
  "${CHAOS}" app dlq order >/dev/null
  echo "injected: one poison message on inventory-updated"
  hold 60
  watch_here \
    "Kafka UI    http://${HOST}:${KAFKA_UI_PORT:-8090} - one record on dead-letter-topic" \
    "LogQL       {service=\"order-service\"} |= \"chaos-poison\"" \
    "Expect      retries with backoff, then exactly one dead-lettered record" \
    "Lesson      nothing consumes the DLQ automatically. A replayed poison message is an outage"
}

scenario_kafka_producer() {
  banner "kafka-producer" "A send the broker refuses, with the cluster perfectly healthy" 9
  "${CHAOS}" app kafka order >/dev/null
  hold 30
  watch_here \
    "LogQL       {service=\"order-service\"} |= \"RecordTooLarge\"" \
    "Expect      an immediate producer-side rejection: no reconnect, no network retry" \
    "Compare     ./scripts/chaos.sh down kafka is the other Kafka failure entirely"
}

scenario_log_burst() {
  banner "log-burst" "The log pipeline takes more than it can carry" 10
  local out
  out="$("${CHAOS}" app logs order 50000 INFO)"
  local burst_id
  burst_id="$(printf '%s' "${out}" | sed -n 's/.*"burstId"[^"]*"\([^"]*\)".*/\1/p')"
  echo "injected: 50,000 lines, burstId=${burst_id}"
  hold 60
  watch_here \
    "LogQL       count_over_time({service=\"order-service\"} |= \"${burst_id}\" [10m])" \
    "Expect      fewer than 50000 if anything dropped - and something usually does" \
    "Lesson      find WHICH component dropped: the async appender, the agent, or Loki"
}

scenario_large_payload() {
  banner "large-payload" "A response big enough for the edge to have opinions" 11
  "${CHAOS}" app payload order 8192
  watch_here \
    "Expect      direct to the service succeeds; through the edge may not" \
    "Note        compare the two byte counts printed above"
}

scenario_internal_traffic() {
  banner "internal-traffic" "Internal pools saturate with no network in the path" 12
  "${CHAOS}" app traffic order 2000 100 >/dev/null
  echo "injected: 2000 internal units of work at concurrency 100"
  hold
  watch_here \
    "PromQL      hikaricp_connections_pending{service=\"order-service\"}" \
    "Expect      the pool saturates without a single extra request at the edge" \
    "Lesson      k6 finds the edge's limit; this finds the application's"
}

scenario_circuit_breaker() {
  banner "circuit-breaker" "Real calls through a genuinely broken dependency" 13
  echo "breaking the gRPC hop first - the breaker only means something against a real failure"
  "${CHAOS}" down inventory-grpc >/dev/null
  sleep 2
  "${CHAOS}" app breaker order 40
  hold 30
  watch_here \
    "PromQL      resilience4j_circuitbreaker_state{name=\"inventory-grpc\"}" \
    "PromQL      resilience4j_circuitbreaker_calls_total{name=\"inventory-grpc\"}" \
    "Expect      CLOSED -> OPEN once the failure rate crosses its threshold" \
    "Then        orders are still accepted as PENDING. That is the design paying off"
}

# --------------------------------------------------------------------------

run_scenario() {
  case "$1" in
    slow-request)     scenario_slow_request ;;
    db-exhaustion)    scenario_db_exhaustion ;;
    cache-silent)     scenario_cache_silent ;;
    cpu-spike)        scenario_cpu_spike ;;
    memory-leak)      scenario_memory_leak ;;
    deadlock)         scenario_deadlock ;;
    exception)        scenario_exception ;;
    dead-letter)      scenario_dead_letter ;;
    kafka-producer)   scenario_kafka_producer ;;
    log-burst)        scenario_log_burst ;;
    large-payload)    scenario_large_payload ;;
    internal-traffic) scenario_internal_traffic ;;
    circuit-breaker)  scenario_circuit_breaker ;;
    *) die "unknown scenario '$1'. Try: $(basename "$0") list" ;;
  esac
}

main() {
  local name="${1:-help}"
  [ $# -gt 0 ] && shift || true

  local arg
  for arg in "$@"; do
    case "${arg}" in
      --under-load) UNDER_LOAD=1 ;;
      *) die "unknown option '${arg}'" ;;
    esac
  done

  case "${name}" in
    help|-h|--help) sed -n '2,29p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; return 0 ;;
    list)           cmd_list; return 0 ;;
  esac

  require_stack
  trap cleanup EXIT INT TERM

  # Never start on a dirty system. A scenario run on top of somebody else's
  # leftover fault produces a result that is an artefact, and the whole value of
  # this script is that its results can be trusted.
  echo "--- clearing any faults left from an earlier run ---"
  "${CHAOS}" reset 2>&1 | sed 's/^/  /'

  start_load_if_requested

  if [ "${name}" = "all" ]; then
    local s
    for s in $(echo "${SCENARIOS}" | cut -d'|' -f1 | grep -v '^$' | grep -v '^deadlock$'); do
      run_scenario "${s}"
      "${CHAOS}" reset >/dev/null 2>&1 || true
      # Deliberate gap between scenarios. Pools drain, breakers half-open and
      # rate windows roll over on their own schedule; running the next fault
      # into the tail of the last one produces a combined signal nobody can
      # attribute.
      echo "--- 30s cool-down ---"
      sleep 30
    done
    echo
    echo "Ran every scenario except 'deadlock', which is permanent and would have"
    echo "poisoned everything after it. Run it on its own when you want it."
  else
    run_scenario "${name}"
  fi
}

main "$@"
