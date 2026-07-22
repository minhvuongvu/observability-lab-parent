# ===========================================================================
# Spring Boot service image - builds either service.
#
# Deliberately no `# syntax=` directive. That line makes every build resolve an
# external frontend image from Docker Hub before it reads a single instruction,
# which is a network round-trip and a failure mode - a transient TLS timeout
# there fails the build having done no work. Nothing here needs it: cache
# mounts, multi-stage and COPY --chown are all in BuildKit's built-in frontend
# and have been for years.
#
#     docker build -f docker/service/Dockerfile --build-arg SERVICE=order-service .
#
# One file rather than one per service, because the two would be identical
# apart from a module name and a port number. Two copies of sixty lines is two
# copies that drift, and the drift shows up as one service quietly running a
# different JDK or a different agent version than the other - which is the last
# thing anyone suspects while comparing their traces.
#
# The build context is the REPOSITORY ROOT, not this directory. The reactor is
# multi-module: each service compiles against shared-library and against stubs
# generated from proto/, so a context scoped to one service cannot build it.
# .dockerignore keeps that context small.
# ===========================================================================

ARG JDK_IMAGE=eclipse-temurin:21-jdk-jammy
ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy

# ---------------------------------------------------------------------------
# Stage 1 - build.
#
# Debian-based, not Alpine, and deliberately. protobuf-maven-plugin downloads a
# protoc binary from Maven Central that is linked against glibc; on musl it
# fails to execute with an error that names neither protoc nor the libc, and
# the build appears to break inside the gRPC stub generation for no reason.
# ---------------------------------------------------------------------------
FROM ${JDK_IMAGE} AS builder

WORKDIR /build

# Dependency layer first. Only the POMs are copied here, so editing application
# code re-runs the compile below without re-resolving a few hundred artifacts.
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
COPY services/shared-library/pom.xml services/shared-library/
COPY services/order-service/pom.xml services/order-service/
COPY services/inventory-service/pom.xml services/inventory-service/

# The whole reactor, not just one module: both service images then share this
# layer and the second one builds almost free.
#
# The cache mount survives across builds without becoming an image layer, which
# is what keeps the ~200 MB local repository out of the shipped image while
# still making a rebuild fast. It needs BuildKit, which is the default in every
# currently supported Docker version.
#
# Failure here is tolerated on purpose, and it is the one `|| true` in this
# repository. go-offline is a cache warm-up, not a build step: it is known to
# fail on reactors using protobuf-maven-plugin, which resolves an OS-classified
# protoc binary that go-offline cannot always see. Any artifact it missed is
# fetched by the package step below, so the only cost of a failure here is a
# slower build - and failing the image build over a prefetch would be trading a
# real capability for a speed optimisation.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -q dependency:go-offline -DskipTests || true

# Sources. proto/ before services/, because the stubs are generated from it.
COPY proto/ proto/
COPY services/ services/

ARG SERVICE
RUN test -n "${SERVICE}" || { echo "ERROR: --build-arg SERVICE is required" >&2; exit 1; }

# -am builds shared-library first. Tests are skipped here on purpose: the image
# build is a packaging step, and running the suite twice - once in CI, once per
# image - buys nothing while making every rebuild minutes longer.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -pl "services/${SERVICE}" -am package -DskipTests

# -------------------------------------------------------------------------
# Runtime Java agents.
#
# These instrument the application from outside it, so no module compiles
# against them and none may reach a service's classpath - which is why they
# are fetched here rather than declared as dependencies.
#
# The versions are read out of pom.xml rather than passed in as build args:
# the parent POM is the single place a version changes, and scripts/agents.sh
# resolves the identical coordinates for the host-run path. Two sources of
# truth here would mean the container and the IDE silently profiling with
# different agents.
# -------------------------------------------------------------------------
#
# The destination file names match the stable symlinks scripts/agents.sh
# creates in tools/, so a path in a compose file and a path in a run script
# name the same thing. io.pyroscope's artifacts are called `agent` and `otel`,
# which say nothing on their own once they are sitting next to each other.
RUN --mount=type=cache,target=/root/.m2 set -eu; \
    pom_version() { sed -n "s|.*<$1>\(.*\)</$1>.*|\1|p" pom.xml | head -1; }; \
    copy_agent() { \
      ./mvnw -B -ntp -q org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy \
        -Dartifact="$1" -DoutputDirectory=/agents -Dmdep.stripVersion=true \
        -Dmdep.overWriteReleases=true; \
      # Only rename when the artifact id is not already the name we want. mv
      # refuses a file onto itself, and under `set -e` that fails the build.
      [ "$2" = "$3" ] || mv "/agents/${2}.jar" "/agents/${3}.jar"; \
    }; \
    mkdir -p /agents; \
    copy_agent "io.opentelemetry.javaagent:opentelemetry-javaagent:$(pom_version opentelemetry-javaagent.version)" \
               opentelemetry-javaagent opentelemetry-javaagent; \
    copy_agent "io.pyroscope:agent:$(pom_version pyroscope-agent.version)" agent pyroscope; \
    copy_agent "io.pyroscope:otel:$(pom_version pyroscope-otel.version)" otel pyroscope-otel

# Fixed name, so the runtime stage does not have to know the project version.
# The wildcard cannot match the .jar.original the Boot repackage leaves behind.
RUN cp "$(ls services/${SERVICE}/target/${SERVICE}-*.jar)" /app.jar

# ---------------------------------------------------------------------------
# Stage 2 - runtime.
#
# JRE, not JDK: the compiler, javadoc and the whole tool chain are build-time
# concerns, and every one of them left in the final image is attack surface
# that cannot be used for anything legitimate at run time.
# ---------------------------------------------------------------------------
FROM ${JRE_IMAGE} AS runtime

# curl for the container healthcheck. The temurin JRE images ship without it,
# and a healthcheck that cannot run reports the container unhealthy forever -
# which compose then reports as a failed dependency, several services away from
# the actual cause.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Non-root, with a fixed uid/gid. Fixed because the log directory is a volume
# shared with the shipping agents: a uid that changes between rebuilds leaves
# files the new container cannot write.
RUN groupadd --system --gid 10001 lab \
 && useradd --system --uid 10001 --gid lab --home-dir /app --shell /usr/sbin/nologin lab

WORKDIR /app

COPY --from=builder --chown=lab:lab /agents/ /opt/agents/
COPY --from=builder --chown=lab:lab /app.jar /app/app.jar

# Where the JSON log file is written, matching app.logging.directory. A named
# volume is mounted here and read by Promtail, Fluent Bit and Fluentd - so the
# directory has to exist and be writable by this uid before the volume is
# populated, or the first write fails and the pipeline stays empty.
RUN mkdir -p /var/log/lab && chown lab:lab /var/log/lab

USER lab

# Documentation only - compose publishes the ports it actually wants exposed.
# 8081/9081 for the Order Service, 8082/9082 for the Inventory Service.
ARG HTTP_PORT=8080
ARG GRPC_PORT=9090
EXPOSE ${HTTP_PORT} ${GRPC_PORT}

# No agent flags here. They are set through JAVA_TOOL_OPTIONS in the compose
# file, where the whole runtime configuration of a service is readable in one
# place and an agent can be switched off by editing one line rather than by
# rebuilding an image.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
