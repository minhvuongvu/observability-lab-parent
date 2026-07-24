#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Drives HashiCorp Vault - the stack's secret store.
#
#   ./scripts/vault.sh bootstrap   init + unseal + seed, idempotent, safe to
#                                  re-run. This is what infra.sh calls.
#
#   ./scripts/vault.sh init        initialise, write the unseal keys
#   ./scripts/vault.sh unseal      unseal using those keys
#   ./scripts/vault.sh seal        seal it again (a fault, see chaos.sh)
#   ./scripts/vault.sh seed        engines, policies, AppRoles, secrets
#   ./scripts/vault.sh status      seal state and mount summary
#
#   ./scripts/vault.sh creds       issue a dynamic PostgreSQL credential and
#                                  show it - each call creates a REAL user
#   ./scripts/vault.sh leases      dynamic credentials currently outstanding
#   ./scripts/vault.sh revoke      revoke them all, immediately
#   ./scripts/vault.sh whoami      which PostgreSQL user the Order Service is
#                                  actually connected as - the proof that the
#                                  credential came from Vault and not from .env
#   ./scripts/vault.sh deny        prove the policy boundary: order-service
#                                  reading inventory-service's path is refused
#   ./scripts/vault.sh audit [n]   tail the audit log
#
# Vault runs a real server, not `-dev`. It therefore boots SEALED and serves
# nothing until unsealed - every restart, not just the first. That is the whole
# reason this script exists rather than a one-shot compose container: the
# unseal keys have to survive the container, and a one-shot cannot hold them.
#
# WHERE THE SECRETS OF THE SECRET STORE LIVE
#
#   docker/compose/.vault-keys.json        unseal keys + initial root token
#   docker/compose/.env.vault.<service>    that service's AppRole credentials
#
# Both git-ignored, both 0600. This is the bootstrap problem and it does not
# have a clean answer at this scale: Vault holds the credentials, and something
# must hold the credential to Vault. What improved is not that the last file on
# disk disappeared - it is that one file replaced twenty scattered values, and
# what it opens is scoped per service, revocable and audited. See docs/Vault.md.
# ---------------------------------------------------------------------------
set -euo pipefail

export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$(cd "${SCRIPT_DIR}/../docker/compose" && pwd)"

CONTAINER="lab-vault"
KEYS_FILE="${COMPOSE_DIR}/.vault-keys.json"

# Five shares, any three to unseal. One key held by one person is a single
# point of both failure and betrayal, which is the threat model Shamir splitting
# exists for. It is theatre on a laptop where all five land in one file - but
# the ceremony is the thing being taught, and a threshold of 1 teaches nothing.
KEY_SHARES=5
KEY_THRESHOLD=3

# Dynamic PostgreSQL credential lifetime.
#
# One hour, renewable to a day. Deliberately not minutes: a credential that dies
# every five minutes turns every experiment into a race, and deliberately not
# the 32-day default, which no reader would ever see expire.
#
# This must stay ABOVE spring.datasource.hikari.max-lifetime (15m in both
# services). The pool recycles a connection every 15 minutes; if the credential
# died first, the pool would hand out connections authenticated with a user
# PostgreSQL has already dropped, and the failure would surface minutes after
# the expiry as a slow trickle of connection errors rather than at the moment
# anything actually changed.
#
# The ceiling is 8h because vault.hcl sets max_lease_ttl there, and a mount
# cannot outlive its server's cap - Vault truncates and warns rather than
# refusing, so a role asking for 24h here would quietly become 8h and the
# configuration would say one thing while the system did another.
DB_DEFAULT_TTL="1h"
DB_MAX_TTL="8h"

die() { echo "ERROR: $*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "$1 is required but not installed."; }

load_env() {
  [ -f "${COMPOSE_DIR}/.env" ] || die "docker/compose/.env not found. Run ./scripts/infra.sh up first."
  # shellcheck disable=SC1090
  set -a && . "${COMPOSE_DIR}/.env" && set +a
}

running() {
  [ "$(docker inspect -f '{{.State.Running}}' "${CONTAINER}" 2>/dev/null || echo false)" = "true" ]
}

require_running() {
  running || die "${CONTAINER} is not running. Start it with: ./scripts/infra.sh up"
}

# The vault CLI inside the container, unauthenticated. VAULT_ADDR is set on the
# container, so these need no address.
v() { docker exec "${CONTAINER}" vault "$@"; }

root_token() {
  [ -f "${KEYS_FILE}" ] || die "${KEYS_FILE} not found. Run: ./scripts/vault.sh init"
  jq -r '.root_token' "${KEYS_FILE}"
}

# The vault CLI inside the container, as root. Used only by seed and by the
# inspection commands - nothing in the running system authenticates this way.
vr() { docker exec -e VAULT_TOKEN="$(root_token)" "${CONTAINER}" vault "$@"; }

# `vault status` deliberately exits 2 when sealed and 1 when unreachable, so its
# exit code carries meaning that `set -o pipefail` would otherwise turn into a
# script failure. The JSON body is complete and correct in all three cases, so
# the code is discarded here and every decision is made from the fields.
vault_status_json() { v status -format=json 2>/dev/null || true; }

# Reads one boolean from that JSON, falling back to $2 when Vault answered
# nothing at all.
#
# NOT `jq -r '.sealed // true'`. jq's `//` yields its right-hand side when the
# left is false OR null, so `.sealed // true` reports a healthy unsealed Vault
# as sealed - and the fallback that was meant to handle "no answer" silently
# swallows the only answer that matters.
vault_field() {
  local out
  out="$(vault_status_json | jq -r ".$1" 2>/dev/null || true)"
  case "${out}" in
    true|false) printf '%s' "${out}" ;;
    *)          printf '%s' "$2" ;;
  esac
}

initialised() { [ "$(vault_field initialized false)" = "true" ]; }

sealed() { [ "$(vault_field sealed true)" = "true" ]; }

# ---------------------------------------------------------------------------
# init
# ---------------------------------------------------------------------------
cmd_init() {
  require_running

  if initialised; then
    echo "Vault is already initialised; leaving it alone."
    [ -f "${KEYS_FILE}" ] || cat >&2 <<EOF

WARNING: Vault is initialised but ${KEYS_FILE} does not exist.
Without those keys this Vault cannot be unsealed and its data is unreachable.
If this is a lab you do not mind losing:

    ./scripts/infra.sh destroy && ./scripts/infra.sh up

EOF
    return 0
  fi

  echo "Initialising Vault (${KEY_SHARES} key shares, threshold ${KEY_THRESHOLD})..."
  # Written with a restrictive umask rather than chmod after the fact: between
  # creat() and chmod() the file is world-readable, and what is in it is every
  # secret in the stack.
  ( umask 077 && v operator init \
      -key-shares="${KEY_SHARES}" \
      -key-threshold="${KEY_THRESHOLD}" \
      -format=json > "${KEYS_FILE}" )

  jq -e '.root_token' "${KEYS_FILE}" >/dev/null \
    || die "Initialisation produced no root token; inspect ${KEYS_FILE}"

  echo "Unseal keys and root token written to ${KEYS_FILE} (0600, git-ignored)."
}

# ---------------------------------------------------------------------------
# unseal
# ---------------------------------------------------------------------------
cmd_unseal() {
  require_running
  initialised || die "Vault is not initialised. Run: ./scripts/vault.sh init"

  if ! sealed; then
    echo "Vault is already unsealed."
    return 0
  fi

  [ -f "${KEYS_FILE}" ] || die "${KEYS_FILE} not found; this Vault cannot be unsealed."

  echo "Unsealing with ${KEY_THRESHOLD} of ${KEY_SHARES} keys..."
  local i key
  for i in $(seq 0 $((KEY_THRESHOLD - 1))); do
    key="$(jq -r ".unseal_keys_b64[${i}]" "${KEYS_FILE}")"
    v operator unseal "${key}" >/dev/null
  done

  sealed && die "Vault is still sealed after ${KEY_THRESHOLD} keys."
  echo "Vault is unsealed."
}

cmd_seal() {
  require_running
  echo "Sealing Vault. Every secret becomes unreadable until it is unsealed again."
  echo "Running services keep working until their next renewal or new connection,"
  echo "which is the whole lesson - see docs/FailureSimulation.md."
  vr operator seal
  echo "Sealed. Recover with: ./scripts/vault.sh unseal"
}

# ---------------------------------------------------------------------------
# seed
#
# Every operation below is idempotent. Re-running seed against a seeded Vault
# rewrites the same values and re-issues AppRole secret-ids, which is the
# behaviour infra.sh depends on: it calls bootstrap on every `up`.
# ---------------------------------------------------------------------------
mount_exists() {
  vr secrets list -format=json 2>/dev/null | jq -e --arg p "$1/" 'has($p)' >/dev/null 2>&1
}

auth_exists() {
  vr auth list -format=json 2>/dev/null | jq -e --arg p "$1/" 'has($p)' >/dev/null 2>&1
}

seed_audit() {
  if vr audit list -format=json 2>/dev/null | jq -e 'has("file/")' >/dev/null 2>&1; then
    echo "  audit device      already enabled"
    return 0
  fi
  # Vault REFUSES every request when all audit devices are failing. That is
  # deliberate - an unauditable secret store is worse than an unavailable one -
  # and it is startling the first time a full disk takes Vault down.
  vr audit enable file file_path=/vault/logs/audit.log >/dev/null
  echo "  audit device      enabled -> /vault/logs/audit.log"
}

seed_kv() {
  if mount_exists secret; then
    echo "  kv-v2 at secret/  already enabled"
  else
    vr secrets enable -path=secret -version=2 kv >/dev/null
    echo "  kv-v2 at secret/  enabled"
  fi

  # Shared. Both services read this, then their own path on top of it.
  #
  # The KEY IS THE SPRING PROPERTY NAME. Spring Cloud Vault contributes these
  # straight into the Environment, so `spring.data.redis.password` here becomes
  # exactly that property - no mapping layer, and a typo produces a silent
  # fallback to the application.yml default rather than an error.
  vr kv put secret/application \
    spring.data.redis.password="${REDIS_PASSWORD}" >/dev/null

  # Order Service. Its runtime datasource credentials are NOT here - those are
  # issued per-lease by the database engine below. What is here is everything
  # that cannot be dynamic:
  #
  # Flyway keeps a STATIC credential, and this is the subtle one. Migrations run
  # DDL, and objects acquire an owner. A short-lived dynamic user would own the
  # schema and then be dropped at the end of its lease, taking the schema with
  # it. So migrations authenticate as the owning role and only the runtime pool
  # uses dynamic credentials.
  vr kv put secret/order-service \
    spring.flyway.user="${ORDER_DB_USER}" \
    spring.flyway.password="${ORDER_DB_PASSWORD}" \
    app.storage.minio.access-key="${MINIO_APP_USER}" \
    app.storage.minio.secret-key="${MINIO_APP_PASSWORD}" >/dev/null

  # Inventory Service. Static datasource credentials, because Oracle has no
  # dynamic engine in the Vault binary - see the header of
  # infrastructure/vault/policies/inventory-service.hcl.
  vr kv put secret/inventory-service \
    spring.datasource.username="${INVENTORY_DB_USER}" \
    spring.datasource.password="${INVENTORY_DB_PASSWORD}" >/dev/null

  echo "  kv secrets        written: application, order-service, inventory-service"
}

# The group role dynamic users inherit from.
#
# Without this, a dynamic user would need its grants spelled out in the creation
# statement - and would then have no rights on any table a later migration adds,
# because the grant was evaluated when the user was created. ALTER DEFAULT
# PRIVILEGES fixes that once, for every user that will ever exist.
seed_postgres_role() {
  docker exec -i -e PGPASSWORD="${POSTGRES_SUPERUSER_PASSWORD}" lab-postgres \
    psql -v ON_ERROR_STOP=1 -q -U "${POSTGRES_SUPERUSER}" -d "${ORDER_DB_NAME}" <<'EOSQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'order_readwrite') THEN
    CREATE ROLE order_readwrite NOLOGIN;
  END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO order_readwrite;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO order_readwrite;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO order_readwrite;
EOSQL

  # Separate statement, and as the owning role: ALTER DEFAULT PRIVILEGES only
  # affects objects created by the role it names.
  docker exec -i -e PGPASSWORD="${POSTGRES_SUPERUSER_PASSWORD}" lab-postgres \
    psql -v ON_ERROR_STOP=1 -q -U "${POSTGRES_SUPERUSER}" -d "${ORDER_DB_NAME}" <<EOSQL
ALTER DEFAULT PRIVILEGES FOR ROLE ${ORDER_DB_USER} IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO order_readwrite;
ALTER DEFAULT PRIVILEGES FOR ROLE ${ORDER_DB_USER} IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO order_readwrite;
EOSQL

  echo "  postgres          group role order_readwrite ready"
}

seed_database() {
  if mount_exists database; then
    echo "  database engine   already enabled"
  else
    vr secrets enable database >/dev/null
    echo "  database engine   enabled"
  fi

  # Vault needs a PostgreSQL account that can CREATE ROLE. The superuser, which
  # is the one credential in this stack that genuinely cannot come from Vault:
  # it is what Vault uses to reach PostgreSQL in the first place.
  vr write database/config/orderdb \
    plugin_name=postgresql-database-plugin \
    allowed_roles=order-service \
    connection_url="postgresql://{{username}}:{{password}}@postgres:5432/${ORDER_DB_NAME}?sslmode=disable" \
    username="${POSTGRES_SUPERUSER}" \
    password="${POSTGRES_SUPERUSER_PASSWORD}" >/dev/null

  vr write database/roles/order-service \
    db_name=orderdb \
    default_ttl="${DB_DEFAULT_TTL}" \
    max_ttl="${DB_MAX_TTL}" \
    creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}' IN ROLE order_readwrite INHERIT;" \
    revocation_statements="REASSIGN OWNED BY \"{{name}}\" TO ${ORDER_DB_USER}; DROP OWNED BY \"{{name}}\"; DROP ROLE IF EXISTS \"{{name}}\";" >/dev/null

  echo "  database role     order-service (ttl ${DB_DEFAULT_TTL}, max ${DB_MAX_TTL})"
}

seed_policies() {
  # Read from the mounted directory inside the container, so the policy text in
  # infrastructure/vault/policies is the single source and nothing is retyped.
  vr policy write order-service /vault/policies/order-service.hcl >/dev/null
  vr policy write inventory-service /vault/policies/inventory-service.hcl >/dev/null
  echo "  policies          order-service, inventory-service"
}

seed_approle() {
  if auth_exists approle; then
    echo "  approle auth      already enabled"
  else
    vr auth enable approle >/dev/null
    echo "  approle auth      enabled"
  fi

  local svc
  for svc in order-service inventory-service; do
    # secret_id_ttl=0 - the credential does not expire on its own.
    #
    # A lab convenience, and named as one: a real deployment issues a
    # short-lived, single-use secret-id through a trusted broker so a leaked one
    # is worthless within minutes. Here it would mean re-seeding every service
    # on a timer, which buys a lesson nobody asked for at the cost of the stack
    # falling over while unattended.
    vr write "auth/approle/role/${svc}" \
      token_policies="${svc}" \
      token_ttl=1h \
      token_max_ttl=8h \
      secret_id_ttl=0 \
      secret_id_num_uses=0 >/dev/null

    local role_id secret_id env_file
    role_id="$(vr read -field=role_id "auth/approle/role/${svc}/role-id")"
    secret_id="$(vr write -f -field=secret_id "auth/approle/role/${svc}/secret-id")"
    env_file="${COMPOSE_DIR}/.env.vault.${svc}"

    ( umask 077 && cat > "${env_file}" <<EOF
# Generated by ./scripts/vault.sh seed - do not edit, do not commit.
# Consumed by docker-compose.services.yml as an env_file for ${svc}.
VAULT_ROLE_ID=${role_id}
VAULT_SECRET_ID=${secret_id}
EOF
    )
    echo "  approle ${svc}$([ "${svc}" = "order-service" ] && echo "    " || echo "") -> $(basename "${env_file}")"
  done
}

cmd_seed() {
  require_running
  load_env
  sealed && die "Vault is sealed. Run: ./scripts/vault.sh unseal"

  echo "Seeding Vault:"
  seed_audit
  seed_kv
  seed_postgres_role
  seed_database
  seed_policies
  seed_approle
  echo "Seed complete."
}

# ---------------------------------------------------------------------------
# bootstrap - what infra.sh calls. Safe to re-run at any time.
# ---------------------------------------------------------------------------
cmd_bootstrap() {
  require_running
  cmd_init
  cmd_unseal
  cmd_seed
}

# ---------------------------------------------------------------------------
# Inspection
# ---------------------------------------------------------------------------
cmd_status() {
  require_running
  v status || true
  if initialised && ! sealed && [ -f "${KEYS_FILE}" ]; then
    echo
    echo "Mounts:"
    vr secrets list 2>/dev/null | sed 's/^/  /'
    echo "Auth:"
    vr auth list 2>/dev/null | sed 's/^/  /'
  fi
}

cmd_creds() {
  require_running
  echo "Issuing a dynamic PostgreSQL credential from database/creds/order-service."
  echo "This CREATES a real PostgreSQL user; it is dropped when the lease ends."
  echo
  vr read database/creds/order-service
  echo
  echo "See it in PostgreSQL with:  ./scripts/vault.sh whoami"
  echo "Revoke everything with:     ./scripts/vault.sh revoke"
}

cmd_leases() {
  require_running
  echo "Outstanding dynamic credentials (database/creds/order-service):"
  vr list -format=json sys/leases/lookup/database/creds/order-service 2>/dev/null \
    | jq -r '.[]' | sed 's/^/  /' || echo "  none"
}

cmd_revoke() {
  require_running
  echo "Revoking every dynamic credential under database/creds/order-service."
  echo "PostgreSQL drops the users immediately. The Order Service keeps serving"
  echo "from its open connections and fails only when the pool next opens one -"
  echo "which is the delay that makes this failure hard to attribute."
  vr lease revoke -prefix -force database/creds/order-service || true
  echo "Revoked."
}

cmd_whoami() {
  load_env
  echo "PostgreSQL sessions on ${ORDER_DB_NAME}, by user:"
  echo
  docker exec -e PGPASSWORD="${POSTGRES_SUPERUSER_PASSWORD}" lab-postgres \
    psql -U "${POSTGRES_SUPERUSER}" -d "${ORDER_DB_NAME}" -A -F'  ' -c \
    "SELECT usename AS user, count(*) AS sessions
       FROM pg_stat_activity
      WHERE datname = '${ORDER_DB_NAME}' AND usename IS NOT NULL
      GROUP BY usename ORDER BY 2 DESC;"
  echo
  echo "A user named v-approle-order-ser-... is a credential Vault minted."
  echo "Seeing '${ORDER_DB_USER}' instead means the service fell back to its"
  echo "application.yml default and is NOT using Vault."
}

cmd_deny() {
  require_running
  echo "Authenticating as order-service, then reading inventory-service's path."
  echo "A policy nobody has tested a denial against is a policy of '*'."
  echo

  local env_file role_id secret_id token
  env_file="${COMPOSE_DIR}/.env.vault.order-service"
  [ -f "${env_file}" ] || die "${env_file} not found. Run: ./scripts/vault.sh seed"
  # shellcheck disable=SC1090
  role_id="$(grep '^VAULT_ROLE_ID=' "${env_file}" | cut -d= -f2)"
  secret_id="$(grep '^VAULT_SECRET_ID=' "${env_file}" | cut -d= -f2)"

  token="$(v write -field=token auth/approle/login \
    role_id="${role_id}" secret_id="${secret_id}")"

  echo "--- its own path (expect: succeeds) ---"
  if docker exec -e VAULT_TOKEN="${token}" "${CONTAINER}" \
       vault kv get -mount=secret order-service >/dev/null 2>&1; then
    echo "  read secret/order-service: PERMITTED"
  else
    echo "  read secret/order-service: DENIED  <- unexpected, the policy is wrong"
  fi

  echo "--- the other service's path (expect: denied) ---"
  if docker exec -e VAULT_TOKEN="${token}" "${CONTAINER}" \
       vault kv get -mount=secret inventory-service >/dev/null 2>&1; then
    echo "  read secret/inventory-service: PERMITTED  <- the boundary does not hold"
    return 1
  else
    echo "  read secret/inventory-service: DENIED"
  fi

  echo
  echo "That denial is now in the audit log:  ./scripts/vault.sh audit"
}

cmd_audit() {
  require_running
  local n="${1:-20}"
  echo "Last ${n} audit records. Every request Vault answered, including refusals."
  echo "Secret VALUES are HMACed, never written in clear - which is what makes"
  echo "this log safe to ship to Loki alongside everything else."
  echo
  docker exec "${CONTAINER}" tail -n "${n}" /vault/logs/audit.log 2>/dev/null \
    | { command -v jq >/dev/null 2>&1 \
        && jq -r '"\(.time)  \(.type)  \(.request.operation // "-")  \(.request.path // "-")  \(.error // "")"' \
        || cat; } \
    || echo "No audit log yet. Has ./scripts/vault.sh seed run?"
}

usage() {
  sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

main() {
  need docker
  need jq

  local cmd="${1:-}"
  [ $# -gt 0 ] && shift || true

  case "${cmd}" in
    bootstrap) cmd_bootstrap ;;
    init)      cmd_init ;;
    unseal)    cmd_unseal ;;
    seal)      cmd_seal ;;
    seed)      cmd_seed ;;
    status)    cmd_status ;;
    creds)     cmd_creds ;;
    leases)    cmd_leases ;;
    revoke)    cmd_revoke ;;
    whoami)    cmd_whoami ;;
    deny)      cmd_deny ;;
    audit)     cmd_audit "$@" ;;
    ""|-h|--help|help) usage ;;
    *)         die "Unknown command: ${cmd}. Run './scripts/vault.sh help'." ;;
  esac
}

main "$@"
