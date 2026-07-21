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
# Built from the published port rather than hard-coded, so this asks the same
# Keycloak the services validate against when the lab has been moved off 8080.
KEYCLOAK_ISSUER="${KEYCLOAK_ISSUER:-http://${BIND_HOST:-127.0.0.1}:${KEYCLOAK_PORT:-8080}/realms/${KEYCLOAK_REALM:-observability}}"

USERNAME="${1:-alice}"
PASSWORD="${2:-${USERNAME}}"
TOKEN_URL="${KEYCLOAK_ISSUER}/protocol/openid-connect/token"

response="$(curl -fsS -X POST "${TOKEN_URL}" \
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
