#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Builds every module of the Observability Lab against a JDK 21 toolchain.
#
#     ./scripts/build.sh                 # clean verify, runs tests
#     ./scripts/build.sh -DskipTests     # any extra argument is passed to Maven
#
# The script resolves JAVA_HOME itself so the build does not depend on which
# JDK happens to be first on PATH.
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# shellcheck source=scripts/toolchain.sh
source "${SCRIPT_DIR}/toolchain.sh"
resolve_java_home

echo "Building with JAVA_HOME=${JAVA_HOME}"
cd "${REPO_ROOT}"
exec ./mvnw -B clean verify "$@"
