package com.observability.lab.shared.chaos;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds on what the chaos endpoints are allowed to do to the process.
 *
 * <p>Every value here is a ceiling, not a setting. The endpoints take their parameters from the
 * caller — that is the point of them — and a caller who asks for a 90-second latency or forty CPU
 * threads gets the ceiling instead, with the clamp reported in the response.
 *
 * <p>The ceilings exist because these faults are indistinguishable from a defect once they are
 * running. A latency toggle left at 90 seconds is a service that appears hung; an unbounded memory
 * leak is an OOM kill that takes the JVM with it before anyone reads the flame graph it was supposed
 * to produce. A lab that destroys itself teaches one lesson once.
 *
 * <p>{@code defaultTtl} is the most important of them. Every toggle expires on its own, so the
 * failure mode of "forgot to reset" is a lab that recovers by itself rather than a debugging session
 * three days later that is chasing a fault somebody injected on purpose and forgot.
 */
@ConfigurationProperties(prefix = "app.chaos")
public record ChaosProperties(
        boolean enabled,
        Duration defaultTtl,
        Duration maxTtl,
        Duration maxLatency,
        int maxCpuThreads,
        Duration maxCpuDuration,
        int maxMemoryLeakMb,
        int maxLogBurstLines,
        int maxPayloadKb,
        int maxTrafficRequests) {

    public ChaosProperties {
        defaultTtl = orDefault(defaultTtl, Duration.ofMinutes(5));
        maxTtl = orDefault(maxTtl, Duration.ofMinutes(30));
        maxLatency = orDefault(maxLatency, Duration.ofSeconds(30));
        maxCpuDuration = orDefault(maxCpuDuration, Duration.ofMinutes(2));
        maxCpuThreads = atLeast(maxCpuThreads, 8);
        maxMemoryLeakMb = atLeast(maxMemoryLeakMb, 512);
        maxLogBurstLines = atLeast(maxLogBurstLines, 100_000);
        maxPayloadKb = atLeast(maxPayloadKb, 64 * 1024);
        maxTrafficRequests = atLeast(maxTrafficRequests, 10_000);
    }

    private static Duration orDefault(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private static int atLeast(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }

    /** Clamps a requested duration to {@code maxTtl}, defaulting when none was asked for. */
    public Duration resolveTtl(Duration requested) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            return defaultTtl;
        }
        return requested.compareTo(maxTtl) > 0 ? maxTtl : requested;
    }
}
