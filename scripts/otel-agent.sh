#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Resolves the OpenTelemetry Java agent into tools/.
#
#     ./scripts/otel-agent.sh          # prints the path, downloading if needed
#
# The agent is a *runtime* artifact, not a dependency: it rewrites bytecode
# from outside the application, so no module compiles against it and it must
# not end up on any service's classpath. Fetching it through Maven rather than
# curl means the version is pinned in one place (the parent POM), the download
# is checksummed by the resolver, and a repeated run is a no-op against the
# local repository.
#
# tools/ is git-ignored: a 20 MB binary does not belong in the repository when
# it can be reproduced exactly from a coordinate.
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# shellcheck source=scripts/toolchain.sh
source "${SCRIPT_DIR}/toolchain.sh"

TOOLS_DIR="${REPO_ROOT}/tools"
AGENT_JAR="${TOOLS_DIR}/opentelemetry-javaagent.jar"

# Read the pinned version from the parent POM so there is exactly one place to
# change it, and no chance of this script and the build disagreeing.
agent_version() {
  sed -n 's|.*<opentelemetry-javaagent.version>\(.*\)</opentelemetry-javaagent.version>.*|\1|p' \
    "${REPO_ROOT}/pom.xml" | head -1
}

VERSION="$(agent_version)"
if [ -z "${VERSION}" ]; then
  echo "ERROR: could not read <opentelemetry-javaagent.version> from pom.xml" >&2
  exit 1
fi

VERSIONED_JAR="${TOOLS_DIR}/opentelemetry-javaagent-${VERSION}.jar"

if [ ! -f "${VERSIONED_JAR}" ]; then
  echo "Resolving OpenTelemetry Java agent ${VERSION}" >&2
  mkdir -p "${TOOLS_DIR}"
  resolve_java_home >&2 || {
    echo "ERROR: a JDK 21+ is required to resolve the agent." >&2
    exit 1
  }
  "${REPO_ROOT}/mvnw" -q \
    org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy \
    -Dartifact="io.opentelemetry.javaagent:opentelemetry-javaagent:${VERSION}" \
    -DoutputDirectory="${TOOLS_DIR}" \
    -Dmdep.stripVersion=false >&2

  # Old versions are left behind rather than deleted: a stale one is harmless
  # and keeping it makes rolling back a version a local operation.
  ln -sf "opentelemetry-javaagent-${VERSION}.jar" "${AGENT_JAR}"
fi

# Refreshed every run so the unversioned name always points at the pinned
# version, even after a version bump.
ln -sf "opentelemetry-javaagent-${VERSION}.jar" "${AGENT_JAR}"

printf '%s\n' "${AGENT_JAR}"
