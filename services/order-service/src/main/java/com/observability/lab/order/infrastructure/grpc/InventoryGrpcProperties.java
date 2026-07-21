package com.observability.lab.order.infrastructure.grpc;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Order Service's half of the gRPC hop: channel tuning, deadlines and breaker thresholds.
 *
 * <p>The deadlines are per operation because no single number is right for all of them, and the
 * reasoning behind each is in the field documentation. The rule they all follow: <strong>a callee's
 * budget must be smaller than its caller's</strong>, or the callee's timeout never fires and the
 * caller times out first — losing the more specific error in the process.
 *
 * @param serviceId              registry name to resolve, not a URL. An instance can move or scale
 *                               without anything here changing
 * @param enabled                whether to use gRPC for the synchronous hop at all. Off leaves the
 *                               REST path in place, which is what makes the two comparable
 * @param checkStockDeadline     single cache-backed point read
 * @param batchCheckDeadline     one {@code IN} query; a larger batch, the same shape
 * @param reserveStockDeadline   deliberately tight. The express reservation is an optimisation, and
 *                               missing it costs nothing but a fall-through to the Kafka path
 * @param keepAliveTime          how often to ping an idle connection. Without keepalive a half-open
 *                               connection is discovered only when an RPC hangs until its deadline
 * @param keepAliveTimeout       how long to wait for the keepalive ack before declaring it dead
 * @param idleTimeout            when to release connections to instances no longer receiving traffic
 * @param maxInboundMessageSize  largest response accepted
 * @param resolverRefresh        how often to re-read the registry for instance changes
 * @param maxRetryAttempts       total attempts, including the first. Beyond three the deadline
 *                               expires anyway and the extra load lands on a struggling dependency
 * @param retryBudgetRatio       cap on retries as a fraction of traffic. Without it a per-attempt
 *                               policy still permits 3x amplification during a partial outage, which
 *                               is the single most common way a retry policy causes the outage it
 *                               was defending against
 * @param circuitBreaker         breaker thresholds
 */
@ConfigurationProperties(prefix = "app.grpc.client.inventory")
public record InventoryGrpcProperties(
        String serviceId,
        boolean enabled,
        Duration checkStockDeadline,
        Duration batchCheckDeadline,
        Duration reserveStockDeadline,
        Duration keepAliveTime,
        Duration keepAliveTimeout,
        Duration idleTimeout,
        int maxInboundMessageSize,
        Duration resolverRefresh,
        int maxRetryAttempts,
        double retryBudgetRatio,
        CircuitBreaker circuitBreaker) {

    /**
     * @param slidingWindowSize    small enough to react quickly, large enough that two failures out
     *                             of three do not trip it
     * @param failureRateThreshold percentage of counted calls that must fail
     * @param slowCallRateThreshold percentage of calls slower than {@code slowCallDuration}.
     *                             <strong>Trips on slowness, not only on errors</strong>: a
     *                             dependency answering every call in five seconds is as damaging as
     *                             one returning errors, and a pure error-rate breaker never notices
     * @param slowCallDuration     what counts as slow
     * @param openDuration         how long the circuit stays open — long enough for a restart
     * @param halfOpenCalls        probes allowed before the breaker decides again
     */
    public record CircuitBreaker(
            int slidingWindowSize,
            float failureRateThreshold,
            float slowCallRateThreshold,
            Duration slowCallDuration,
            Duration openDuration,
            int halfOpenCalls) {

        public CircuitBreaker {
            slidingWindowSize = slidingWindowSize == 0 ? 20 : slidingWindowSize;
            failureRateThreshold = failureRateThreshold == 0 ? 50f : failureRateThreshold;
            slowCallRateThreshold = slowCallRateThreshold == 0 ? 80f : slowCallRateThreshold;
            slowCallDuration = slowCallDuration == null ? Duration.ofMillis(250) : slowCallDuration;
            openDuration = openDuration == null ? Duration.ofSeconds(10) : openDuration;
            halfOpenCalls = halfOpenCalls == 0 ? 3 : halfOpenCalls;
        }
    }

    public InventoryGrpcProperties {
        serviceId = serviceId == null || serviceId.isBlank() ? "inventory-service" : serviceId;
        checkStockDeadline = orDefault(checkStockDeadline, Duration.ofMillis(200));
        batchCheckDeadline = orDefault(batchCheckDeadline, Duration.ofMillis(300));
        reserveStockDeadline = orDefault(reserveStockDeadline, Duration.ofMillis(200));
        keepAliveTime = orDefault(keepAliveTime, Duration.ofSeconds(30));
        keepAliveTimeout = orDefault(keepAliveTimeout, Duration.ofSeconds(10));
        idleTimeout = orDefault(idleTimeout, Duration.ofMinutes(5));
        maxInboundMessageSize = maxInboundMessageSize == 0 ? 4 * 1024 * 1024 : maxInboundMessageSize;
        resolverRefresh = orDefault(resolverRefresh, Duration.ofSeconds(10));
        maxRetryAttempts = maxRetryAttempts == 0 ? 3 : maxRetryAttempts;
        retryBudgetRatio = retryBudgetRatio == 0 ? 0.2 : retryBudgetRatio;
        circuitBreaker = circuitBreaker == null
                ? new CircuitBreaker(0, 0, 0, null, null, 0)
                : circuitBreaker;
    }

    private static Duration orDefault(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }
}
