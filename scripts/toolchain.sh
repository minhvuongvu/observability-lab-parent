#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Shared toolchain resolution for the Observability Lab build scripts.
#
# This file is meant to be sourced, not executed:
#
#     source "$(dirname "${BASH_SOURCE[0]}")/toolchain.sh"
#     resolve_java_home
#
# resolve_java_home leaves a JDK 21 or newer in JAVA_HOME, or returns non-zero
# with an actionable message. It exists because the JDK on PATH is frequently
# not the JDK the project targets, and a wrong-JDK build fails deep in the
# reactor with an error that does not name the real cause.
# ---------------------------------------------------------------------------

REQUIRED_JAVA_MAJOR=21

# Prints the feature (major) version of the JDK rooted at $1.
# Prints nothing when the path does not hold a usable JDK.
java_major_of() {
  local home="$1"
  [ -x "${home}/bin/javac" ] || return 0
  "${home}/bin/javac" -version 2>&1 | awk '{print $2}' | cut -d. -f1
}

# Exports JAVA_HOME pointing at the first candidate JDK that satisfies the
# required feature version. Returns 1 when nothing suitable is installed.
resolve_java_home() {
  local candidates=()

  # An explicit JAVA_HOME always wins, provided it is new enough.
  [ -n "${JAVA_HOME:-}" ] && candidates+=("${JAVA_HOME}")

  # macOS JDK registry.
  if [ -x /usr/libexec/java_home ]; then
    local mac_home
    mac_home="$(/usr/libexec/java_home -v "${REQUIRED_JAVA_MAJOR}" 2>/dev/null || true)"
    [ -n "${mac_home}" ] && candidates+=("${mac_home}")
  fi

  # Homebrew, Apple silicon then Intel.
  candidates+=("/opt/homebrew/opt/openjdk@${REQUIRED_JAVA_MAJOR}/libexec/openjdk.jdk/Contents/Home")
  candidates+=("/usr/local/opt/openjdk@${REQUIRED_JAVA_MAJOR}/libexec/openjdk.jdk/Contents/Home")

  # SDKMAN.
  candidates+=("${HOME}/.sdkman/candidates/java/${REQUIRED_JAVA_MAJOR}-tem")
  candidates+=("${HOME}/.sdkman/candidates/java/current")

  # Common Linux distribution layouts.
  candidates+=("/usr/lib/jvm/java-${REQUIRED_JAVA_MAJOR}-openjdk-amd64")
  candidates+=("/usr/lib/jvm/java-${REQUIRED_JAVA_MAJOR}-openjdk-arm64")
  candidates+=("/usr/lib/jvm/java-${REQUIRED_JAVA_MAJOR}-openjdk")

  local candidate major
  for candidate in "${candidates[@]}"; do
    [ -d "${candidate}" ] || continue
    major="$(java_major_of "${candidate}")"
    [ -n "${major}" ] || continue
    if [ "${major}" -ge "${REQUIRED_JAVA_MAJOR}" ] 2>/dev/null; then
      export JAVA_HOME="${candidate}"
      return 0
    fi
  done

  cat >&2 <<EOF
ERROR: no JDK ${REQUIRED_JAVA_MAJOR} or newer was found.

This project compiles to Java ${REQUIRED_JAVA_MAJOR} bytecode and the build enforces the
toolchain floor, so an older JDK cannot be used. Install one and retry:

  macOS   brew install openjdk@${REQUIRED_JAVA_MAJOR}
  Debian  sudo apt-get install openjdk-${REQUIRED_JAVA_MAJOR}-jdk
  SDKMAN  sdk install java ${REQUIRED_JAVA_MAJOR}-tem

Alternatively export JAVA_HOME yourself to point at an existing installation.
EOF
  return 1
}
