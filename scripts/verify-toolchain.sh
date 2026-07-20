#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Reports whether this machine can build and run the Observability Lab.
#
#     ./scripts/verify-toolchain.sh
#
# Exits non-zero when a component required to build is missing. Components that
# are only needed to run the infrastructure are reported but do not fail the
# check, so the script stays useful before Docker is set up.
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/toolchain.sh
source "${SCRIPT_DIR}/toolchain.sh"

status=0

echo "Observability Lab - toolchain check"
echo

# --- Required to build ------------------------------------------------------
echo "Required to build:"

if resolve_java_home; then
  echo "  [ok]   JDK        $("${JAVA_HOME}/bin/javac" -version 2>&1 | awk '{print $2}')  (${JAVA_HOME})"
else
  echo "  [FAIL] JDK        not found, need ${REQUIRED_JAVA_MAJOR} or newer"
  status=1
fi

if [ -x "${SCRIPT_DIR}/../mvnw" ]; then
  echo "  [ok]   Maven      wrapper present (./mvnw pins the Maven version)"
elif command -v mvn >/dev/null 2>&1; then
  echo "  [ok]   Maven      $(mvn -version 2>/dev/null | head -1 | awk '{print $3}')"
else
  echo "  [FAIL] Maven      neither ./mvnw nor mvn is available"
  status=1
fi

# --- Required to run the infrastructure ------------------------------------
echo
echo "Required to run the infrastructure:"

if command -v docker >/dev/null 2>&1; then
  echo "  [ok]   Docker     $(docker --version 2>/dev/null | awk '{print $3}' | tr -d ',')"
  if docker compose version >/dev/null 2>&1; then
    echo "  [ok]   Compose    $(docker compose version --short 2>/dev/null)"
  else
    echo "  [warn] Compose    docker compose plugin not available"
  fi
  if docker info >/dev/null 2>&1; then
    echo "  [ok]   Daemon     reachable"
  else
    echo "  [warn] Daemon     not reachable, start Docker before bringing infrastructure up"
  fi
else
  echo "  [warn] Docker     not installed, needed from step 02 onwards"
fi

echo
if [ "${status}" -eq 0 ]; then
  echo "Result: ready to build. Run ./scripts/build.sh"
else
  echo "Result: build prerequisites are missing, see the failures above."
fi

exit "${status}"
