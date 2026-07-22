package com.observability.lab.shared.chaos;

import java.time.Instant;
import java.util.Map;

/**
 * One active fault: what it is, how it is parameterised, and when it stops on its own.
 *
 * <p>{@code ratio} is the field that makes a scenario realistic. A fault applied to every request is
 * easy to see and easy to diagnose; a fault applied to one request in ten leaves the mean latency
 * looking healthy while the 99th percentile is on fire, which is what a real partial failure does and
 * is the case dashboards are actually bad at.
 *
 * @param name the fault's identifier, matching its endpoint
 * @param ratio fraction of affected operations, 0..1
 * @param attributes fault-specific parameters, surfaced verbatim by {@code GET /api/v1/chaos}
 * @param injectedAt when it was switched on
 * @param expiresAt when it switches itself off
 */
public record ChaosToggle(
        String name, double ratio, Map<String, Object> attributes, Instant injectedAt, Instant expiresAt) {

    public ChaosToggle {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        ratio = Math.clamp(ratio, 0.0d, 1.0d);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public long remainingSeconds(Instant now) {
        if (expiresAt == null) {
            return 0L;
        }
        return Math.max(0L, expiresAt.getEpochSecond() - now.getEpochSecond());
    }
}
