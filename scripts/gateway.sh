#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Inspects and reloads the edge.
#
#     ./scripts/gateway.sh validate   parse kong.yml without applying it
#     ./scripts/gateway.sh reload     apply kong.yml, then reload Nginx
#     ./scripts/gateway.sh routes     what the gateway currently routes
#     ./scripts/gateway.sh health     upstream target health as Kong sees it
#     ./scripts/gateway.sh plugins    which plugins are active, and where
#     ./scripts/gateway.sh status     all of the above, briefly
#
# Kong runs DB-less, so kong.yml is the whole of its state: reloading is how a
# configuration change takes effect, and validating first is how a typo becomes
# an error message instead of a gateway that refuses every request.
# ---------------------------------------------------------------------------
set -euo pipefail

# Git Bash rewrites arguments that look like absolute POSIX paths into Windows
# paths before handing them to a program, so `docker exec kong ... /opt/kong/kong.yml`
# arrives inside the container as `C:/Program Files/Git/opt/kong/kong.yml` and
# the file is reported missing. These disable that translation; they are ignored
# everywhere else.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${REPO_ROOT}/docker/compose/.env"

KONG_CONTAINER=lab-kong
NGINX_CONTAINER=lab-nginx
DECLARATIVE_CONFIG=/opt/kong/kong.yml

die() { echo "ERROR: $*" >&2; exit 1; }

# Runs the interpreter that actually works, for the admin-API formatting below.
# macOS and Linux have `python3`; Windows has `python`, and there `python3` is a
# Microsoft Store alias stub that prints "Python was not found" and exits
# non-zero - so each candidate is probed by really executing it, not by mere
# presence on PATH. Resolved lazily: `validate` needs no interpreter and must
# keep working on a machine without one.
py() {
  if [ -z "${PYTHON:-}" ]; then
    local candidate
    for candidate in python3 python; do
      if command -v "${candidate}" >/dev/null 2>&1 \
          && "${candidate}" -c 'import sys' >/dev/null 2>&1; then
        PYTHON="$(command -v "${candidate}")"
        break
      fi
    done
    [ -n "${PYTHON:-}" ] || die "a working Python 3 is required for this command but none was found."
  fi
  "${PYTHON}" "$@"
}

if [ -f "${ENV_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a; . "${ENV_FILE}"; set +a
fi
ADMIN="http://${BIND_HOST:-127.0.0.1}:${KONG_ADMIN_PORT:-8001}"

require_kong() {
  docker inspect -f '{{.State.Running}}' "${KONG_CONTAINER}" 2>/dev/null | grep -q true \
    || die "${KONG_CONTAINER} is not running. Start the stack: ./scripts/infra.sh up"
}

# Pretty-prints a JSON document from the admin API without needing jq.
admin() {
  curl -fsS --max-time 10 "${ADMIN}$1"
}

cmd_validate() {
  require_kong
  # Parses in the container, against the same file Kong actually reads, so a
  # bind-mount that is stale or missing is caught here rather than at reload.
  if docker exec "${KONG_CONTAINER}" kong config parse "${DECLARATIVE_CONFIG}" >/dev/null 2>&1; then
    echo "kong.yml is valid"
  else
    echo "kong.yml is NOT valid:" >&2
    docker exec "${KONG_CONTAINER}" kong config parse "${DECLARATIVE_CONFIG}" || true
    return 1
  fi
}

# Kong's DB-less fingerprint of the configuration currently being served.
config_hash() {
  admin /status 2>/dev/null | py -c '
import json, sys
print(json.load(sys.stdin).get("configuration_hash", ""))' 2>/dev/null || true
}

cmd_reload() {
  cmd_validate

  local before after
  before="$(config_hash)"
  docker exec "${KONG_CONTAINER}" kong reload >/dev/null

  # `kong reload` returns as soon as it has signalled the master process. The
  # workers are respawned asynchronously, so for a short window the gateway is
  # still serving the OLD configuration. Returning immediately makes a test run
  # straight afterwards silently exercise the previous config and report that
  # the change "did not work" - which is exactly the false conclusion this wait
  # exists to prevent.
  local waited=0
  while [ "${waited}" -lt 30 ]; do
    after="$(config_hash)"
    if [ -n "${after}" ] && [ "${after}" != "${before}" ]; then
      echo "Kong reloaded (configuration ${after:0:12})"
      break
    fi
    sleep 0.5
    waited=$((waited + 1))
  done

  if [ "${waited}" -ge 30 ]; then
    # Not an error: reloading an unchanged file legitimately leaves the hash
    # alone. Saying so is more useful than either claiming success or failing.
    echo "Kong reloaded (configuration unchanged)"
  fi

  # Nginx resolves the Kong container's address once, at startup. If Kong was
  # recreated rather than reloaded, Nginx would keep proxying to an address that
  # no longer exists, so it is reloaded alongside.
  if docker inspect -f '{{.State.Running}}' "${NGINX_CONTAINER}" 2>/dev/null | grep -q true; then
    docker exec "${NGINX_CONTAINER}" nginx -s reload >/dev/null 2>&1 && echo "Nginx reloaded"
  fi
}

cmd_routes() {
  require_kong
  admin /routes | py -c '
import json, sys
routes = json.load(sys.stdin)["data"]
if not routes:
    print("  (no routes configured)")
for r in sorted(routes, key=lambda x: x["name"] or ""):
    methods = ",".join(r.get("methods") or ["ANY"])
    paths = " ".join(r.get("paths") or [])
    print("  %-12s %-40s %s" % (r["name"], paths, methods))'
}

cmd_health() {
  require_kong
  local names name
  names="$(admin /upstreams | py -c '
import json, sys
print("\n".join(u["name"] for u in json.load(sys.stdin)["data"]))')"

  if [ -z "${names}" ]; then
    echo "  (no upstreams configured)"
    return 0
  fi

  for name in ${names}; do
    echo "  ${name}"
    admin "/upstreams/${name}/health" | py -c '
import json, sys
for target in json.load(sys.stdin)["data"]:
    print("      %-34s %s" % (target["target"], target.get("health", "?")))'
  done
}

cmd_plugins() {
  require_kong
  admin /plugins | py -c '
import json, sys
plugins = json.load(sys.stdin)["data"]
if not plugins:
    print("  (no plugins configured)")
for p in sorted(plugins, key=lambda x: x["name"]):
    scope = "global"
    for key in ("service", "route", "consumer"):
        if p.get(key):
            scope = key
    state = "enabled" if p["enabled"] else "DISABLED"
    print("  %-22s %-10s %s" % (p["name"], scope, state))'
}

cmd_status() {
  echo "Routes"; cmd_routes
  echo; echo "Plugins"; cmd_plugins
  echo; echo "Upstream health"; cmd_health
}

case "${1:-status}" in
  validate) cmd_validate ;;
  reload)   cmd_reload ;;
  routes)   cmd_routes ;;
  health)   cmd_health ;;
  plugins)  cmd_plugins ;;
  status)   cmd_status ;;
  help|-h|--help) sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  *) die "unknown command '${1}'. Try: validate reload routes health plugins status" ;;
esac
