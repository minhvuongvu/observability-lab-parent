#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Resolves the runtime Java agents into tools/.
#
#     ./scripts/agents.sh otel        # OpenTelemetry javaagent  (step 12)
#     ./scripts/agents.sh pyroscope   # Pyroscope profiler agent (step 13)
#     ./scripts/agents.sh pyroscope-otel   # Pyroscope's OTel extension
#     ./scripts/agents.sh all         # all of them, one path per line
#
# These are *runtime* artifacts, not dependencies. They instrument the
# application from outside it, so no module compiles against them and none of
# them may end up on a service's classpath.
#
# Fetching them through Maven rather than curl means each version is pinned in
# one place - the parent POM - the download is checksummed by the resolver, and
# a repeated run is a no-op against the local repository.
#
# tools/ is git-ignored: binaries that can be reproduced exactly from a
# coordinate do not belong in the repository.
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# shellcheck source=scripts/toolchain.sh
source "${SCRIPT_DIR}/toolchain.sh"

TOOLS_DIR="${REPO_ROOT}/tools"

# Reads a pinned version from the parent POM, so there is exactly one place to
# change it and no chance of this script and the build disagreeing.
pom_version() {
  sed -n "s|.*<$1>\(.*\)</$1>.*|\1|p" "${REPO_ROOT}/pom.xml" | head -1
}

# resolve <property-name> <groupId:artifactId> <stable-symlink-name>
resolve() {
  local property="$1" coordinates="$2" link_name="$3"
  local version artifact_id versioned link

  version="$(pom_version "${property}")"
  if [ -z "${version}" ]; then
    echo "ERROR: could not read <${property}> from pom.xml" >&2
    return 1
  fi

  artifact_id="${coordinates#*:}"
  versioned="${TOOLS_DIR}/${artifact_id}-${version}.jar"
  link="${TOOLS_DIR}/${link_name}"

  if [ ! -f "${versioned}" ]; then
    echo "Resolving ${coordinates}:${version}" >&2
    mkdir -p "${TOOLS_DIR}"
    resolve_java_home >&2 || {
      echo "ERROR: a JDK 21+ is required to resolve the agents." >&2
      return 1
    }
    "${REPO_ROOT}/mvnw" -q \
      org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy \
      -Dartifact="${coordinates}:${version}" \
      -DoutputDirectory="${TOOLS_DIR}" \
      -Dmdep.stripVersion=false >&2
  fi

  # Refreshed every run so the stable name always points at the pinned version,
  # even immediately after a version bump.
  ln -sf "${artifact_id}-${version}.jar" "${link}"
  printf '%s\n' "${link}"
}

case "${1:-all}" in
  otel)
    resolve opentelemetry-javaagent.version \
      io.opentelemetry.javaagent:opentelemetry-javaagent opentelemetry-javaagent.jar
    ;;
  pyroscope)
    resolve pyroscope-agent.version io.pyroscope:agent pyroscope.jar
    ;;
  pyroscope-otel)
    resolve pyroscope-otel.version io.pyroscope:otel pyroscope-otel.jar
    ;;
  all)
    resolve opentelemetry-javaagent.version \
      io.opentelemetry.javaagent:opentelemetry-javaagent opentelemetry-javaagent.jar
    resolve pyroscope-agent.version io.pyroscope:agent pyroscope.jar
    resolve pyroscope-otel.version io.pyroscope:otel pyroscope-otel.jar
    ;;
  *)
    echo "usage: $(basename "$0") [otel|pyroscope|pyroscope-otel|all]" >&2
    exit 1
    ;;
esac
