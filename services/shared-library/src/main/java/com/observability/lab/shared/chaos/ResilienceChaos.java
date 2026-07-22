package com.observability.lab.shared.chaos;

import java.util.Map;

/**
 * Drives and reports the resilience machinery guarding a service's outbound calls.
 *
 * <p>Only a service that <em>has</em> such machinery implements this — in this platform, the Order
 * Service, whose gRPC channel to Inventory carries a retry policy and a circuit breaker.
 *
 * <p>Note what the driver deliberately does not do: it does not fake a failure. It issues real calls
 * through the real client, so whether the breaker opens depends on whether the dependency is actually
 * broken. Combining it with a network fault is the whole scenario:
 *
 * <pre>
 *   ./scripts/chaos.sh down inventory-grpc      # break the dependency for real
 *   POST /api/v1/chaos/circuit-breaker          # drive calls through it and watch the breaker trip
 * </pre>
 *
 * <p>An endpoint that simply set the breaker to OPEN would demonstrate that a state field can be
 * assigned. It would not demonstrate that the failure-rate threshold, the sliding window and the
 * slow-call detector are configured such that a real outage trips them, which is the only claim worth
 * testing.
 */
public interface ResilienceChaos {

    /** Current breaker state, failure rate, and buffered call counts. */
    Map<String, Object> circuitBreakerState();

    /**
     * Issues {@code calls} real requests through the guarded client, reporting the breaker state
     * before and after along with a tally of outcomes.
     */
    Map<String, Object> driveCalls(int calls);
}
