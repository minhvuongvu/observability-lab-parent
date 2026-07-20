package com.observability.lab.shared.correlation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who this service is, stamped onto every log line, metric tag and span attribute.
 *
 * <p>Bound from the {@code app.*} properties each service declares:
 *
 * <pre>{@code
 * app:
 *   name: ${spring.application.name}
 *   version: @project.version@      # filtered in from the Maven coordinates at build time
 *   environment: local              # overridden per profile
 * }</pre>
 *
 * <p>Sourcing the version from the build rather than a hand-maintained constant means it cannot
 * drift from the artifact it describes, which is what makes "this started failing in 1.4.2" a
 * statement you can trust.
 *
 * <p>Every field falls back to a placeholder rather than null. Telemetry tagged {@code unknown} is a
 * misconfiguration you can see and fix; telemetry tagged null is a field that silently disappears
 * from the log line and takes the question with it.
 *
 * @param name        logical service name
 * @param version     build version
 * @param environment deployment environment
 */
@ConfigurationProperties(prefix = "app")
public record ServiceIdentity(String name, String version, String environment) {

    private static final String UNKNOWN = "unknown";

    public ServiceIdentity {
        name = orDefault(name, "unknown-service");
        version = orDefault(version, UNKNOWN);
        environment = orDefault(environment, UNKNOWN);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
