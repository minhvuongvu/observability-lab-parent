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

# Path to an executable inside a JDK, accounting for the .exe suffix that
# Windows adds. Prints nothing when neither form is executable.
jdk_tool() {
  local home="$1" tool="$2"
  if [ -x "${home}/bin/${tool}" ]; then
    printf '%s' "${home}/bin/${tool}"
  elif [ -x "${home}/bin/${tool}.exe" ]; then
    printf '%s' "${home}/bin/${tool}.exe"
  fi
}

# Prints the java launcher for the resolved JAVA_HOME. Scripts must use this
# rather than "${JAVA_HOME}/bin/java", which does not exist on Windows.
java_bin() {
  jdk_tool "${JAVA_HOME:-}" java
}

# Prints the full version string of the JDK rooted at $1, for display.
java_version_of() {
  local javac
  javac="$(jdk_tool "$1" javac)"
  [ -n "${javac}" ] || return 0
  "${javac}" -version 2>&1 | awk '{print $2}'
}

# Prints the feature (major) version of the JDK rooted at $1.
# Prints nothing when the path does not hold a usable JDK.
java_major_of() {
  local home="$1" javac
  javac="$(jdk_tool "${home}" javac)"
  [ -n "${javac}" ] || return 0
  "${javac}" -version 2>&1 | awk '{print $2}' | cut -d. -f1
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

  # Windows, running under Git Bash or MSYS. Vendors install into Program Files
  # under their own directory name, so each is globbed rather than guessed at
  # exactly. A glob that matches nothing expands to itself and is filtered out
  # by the directory test below.
  local program_files
  for program_files in "${PROGRAMFILES:-}" "${ProgramW6432:-}" "/c/Program Files"; do
    [ -n "${program_files}" ] && [ -d "${program_files}" ] || continue
    for candidate in \
        "${program_files}"/Java/jdk-2[1-9]* \
        "${program_files}"/Eclipse\ Adoptium/jdk-2[1-9]* \
        "${program_files}"/Microsoft/jdk-2[1-9]* \
        "${program_files}"/Amazon\ Corretto/jdk2[1-9]* \
        "${program_files}"/Zulu/zulu-2[1-9]* \
        "${program_files}"/BellSoft/LibericaJDK-2[1-9]* \
        "${program_files}"/RedHat/java-2[1-9]*; do
      [ -d "${candidate}" ] && candidates+=("${candidate}")
    done
  done

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

  macOS    brew install openjdk@${REQUIRED_JAVA_MAJOR}
  Debian   sudo apt-get install openjdk-${REQUIRED_JAVA_MAJOR}-jdk
  Windows  winget install EclipseAdoptium.Temurin.${REQUIRED_JAVA_MAJOR}.JDK
  SDKMAN   sdk install java ${REQUIRED_JAVA_MAJOR}-tem

Alternatively export JAVA_HOME yourself to point at an existing installation.
On Windows use a Git Bash style path, for example:

  export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-${REQUIRED_JAVA_MAJOR}.0.0.0-hotspot"
EOF
  return 1
}
