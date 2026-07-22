package com.observability.lab.shared.chaos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * Request bodies for the chaos endpoints.
 *
 * <p>Validated, which looks fussy on an endpoint whose purpose is to break things. It is not: a
 * negative delay or a zero ratio produces a fault that silently does nothing, and an experiment that
 * quietly did not run is worse than one that failed loudly. The ceilings in {@link ChaosProperties}
 * catch the other end.
 *
 * <p>Every request carries {@code ttlSeconds}. Omitted, it takes the configured default — no fault
 * here is permanent by accident.
 */
public final class ChaosRequests {

    private ChaosRequests() {
    }

    /** {@code POST /api/v1/chaos/latency} */
    public record Latency(
            @Min(1) @Max(120_000) long delayMs,
            @Min(0) @Max(1) double ratio,
            @Min(0) long ttlSeconds) {

        public Latency {
            ratio = ratio <= 0 ? 1.0d : ratio;
        }
    }

    /** {@code POST /api/v1/chaos/exception} */
    public record Exception(
            @Min(0) @Max(1) double ratio,
            @Min(0) long ttlSeconds,
            String message) {

        public Exception {
            ratio = ratio <= 0 ? 1.0d : ratio;
        }
    }

    /** {@code POST /api/v1/chaos/cpu} */
    public record Cpu(
            @Min(1) @Max(64) int threads,
            @Min(1) @Max(600) long durationSeconds) {
    }

    /** {@code POST /api/v1/chaos/memory-leak} */
    public record MemoryLeak(@Min(1) @Max(4096) int megabytes) {
    }

    /** {@code POST /api/v1/chaos/log-burst} */
    public record LogBurst(
            @Min(1) int lines,
            @Pattern(regexp = "(?i)DEBUG|INFO|WARN|ERROR") String level,
            @Min(0) @Max(4096) int paddingBytes) {

        public LogBurst {
            level = level == null || level.isBlank() ? "INFO" : level;
        }
    }

    /** {@code POST /api/v1/chaos/traffic} */
    public record Traffic(
            @Min(1) int requests,
            @Min(1) @Max(512) int concurrency,
            @Min(0) @Max(60_000) long workMillis) {
    }

    /** {@code POST /api/v1/chaos/db-slow} */
    public record DatabaseSlow(
            @Min(1) @Max(300) long seconds,
            @Min(1) @Max(256) int connections) {

        public DatabaseSlow {
            connections = connections <= 0 ? 1 : connections;
        }
    }

    /** {@code POST /api/v1/chaos/cache} */
    public record Cache(
            @Pattern(regexp = "miss|fail") String mode,
            @Min(0) @Max(1) double ratio,
            @Min(0) long ttlSeconds) {

        public Cache {
            mode = mode == null || mode.isBlank() ? ChaosCacheManager.MODE_MISS : mode;
            ratio = ratio <= 0 ? 1.0d : ratio;
        }
    }

    /** {@code POST /api/v1/chaos/circuit-breaker} */
    public record CircuitBreaker(@Min(1) @Max(500) int calls) {
    }
}
