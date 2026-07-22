#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Fetches a Keycloak access token via the OAuth2 password grant, for exercising
# the protected APIs by hand.
#
#     ./scripts/token.sh                      # alice  (realm role USER)
#     ./scripts/token.sh manager              # manager (realm roles ADMIN, USER)
#     ./scripts/token.sh alice alice          # explicit password
#
#     TOKEN=$(./scripts/token.sh manager)
#     curl -H "Authorization: Bearer ${TOKEN}" http://localhost/api/v1/orders
#
# The token's issuer is http://keycloak:8080/realms/observability - an address
# on the Docker network, not on the host. See the block below for why.
#
# Only the token is written to stdout, so command substitution captures it
# cleanly; everything else goes to stderr.
#
# The password grant is a lab convenience, not a pattern for production - a real
# client uses the authorization-code flow so the password never reaches it. It
# is enabled here on a public client purely so a token is one command away.
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${REPO_ROOT}/docker/compose/.env"

# Defaults describe the standard local stack; .env overrides them when the
# ports or realm have been changed.
KEYCLOAK_CLIENT_ID="swagger-ui"
if [ -f "${ENV_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a; . "${ENV_FILE}"; set +a
fi
# ---------------------------------------------------------------------------
# Two different addresses, and the distinction is the whole subtlety here.
#
# Keycloak derives the `iss` claim from the Host header of the request that
# minted the token. Everything in the stack - both services, Kong's JWT
# consumer, k6 - reaches Keycloak as `keycloak:8080` on lab-net and validates
# against that exact issuer string. A token fetched over localhost would carry
# `http://localhost:8080/...` instead and be rejected with a 401 that looks like
# an authorization bug and is actually a hostname.
#
# So: connect over the published port, because `keycloak` does not resolve on
# the host - but send `Host: keycloak:8080`, so the token that comes back is
# byte-identical to one minted from inside the network.
# ---------------------------------------------------------------------------
KEYCLOAK_ISSUER="${KEYCLOAK_ISSUER:-http://keycloak:8080/realms/${KEYCLOAK_REALM:-observability}}"
# Where to actually send the bytes.
KEYCLOAK_ADDR="http://${BIND_HOST:-127.0.0.1}:${KEYCLOAK_PORT:-8080}"
# The Host header that makes the issuer come out right, taken from the issuer
# itself so the two cannot drift.
KEYCLOAK_HOST_HEADER="$(printf '%s' "${KEYCLOAK_ISSUER}" | sed -e 's|^https\{0,1\}://||' -e 's|/.*$||')"

USERNAME="${1:-alice}"
PASSWORD="${2:-${USERNAME}}"
TOKEN_URL="${KEYCLOAK_ADDR}/realms/${KEYCLOAK_REALM:-observability}/protocol/openid-connect/token"

response="$(curl -fsS -X POST "${TOKEN_URL}" \
  -H "Host: ${KEYCLOAK_HOST_HEADER}" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "client_id=${KEYCLOAK_CLIENT_ID}" \
  -d "username=${USERNAME}" \
  -d "password=${PASSWORD}" \
  -d 'grant_type=password')" || {
    echo "ERROR: token request to ${TOKEN_URL} failed." >&2
    echo "       Is Keycloak up? Check with ./scripts/infra.sh health" >&2
    exit 1
  }

# Pull the access_token out without depending on jq. A JWT contains no double
# quote, so a non-greedy character-class capture is exact.
token="$(printf '%s' "${response}" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"

if [ -z "${token}" ]; then
  echo "ERROR: no access_token in the response from ${TOKEN_URL}:" >&2
  echo "${response}" >&2
  echo "Hint: is the user '${USERNAME}' correct, and the realm imported?" >&2
  exit 1
fi

printf '%s\n' "${token}"
