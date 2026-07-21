package com.observability.lab.order.infrastructure.grpc;

import com.observability.lab.inventory.grpc.v1.InventoryServiceGrpc;
import com.observability.lab.shared.grpc.GrpcCorrelationClientInterceptor;
import com.observability.lab.shared.grpc.GrpcDeadlineClientInterceptor;
import com.observability.lab.shared.grpc.GrpcMetricsClientInterceptor;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.grpc.ClientInterceptor;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolverRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The channel to the Inventory Service, and the reliability policy around it.
 *
 * <p><strong>One channel per target service, shared across the application.</strong> A channel is
 * expensive to create and cheap to share; creating one per call is the gRPC equivalent of opening a
 * new database connection per query, and it also defeats the connection reuse the protocol exists
 * for.
 *
 * <p>Retry policy lives in the channel's service config rather than in code, so it is data that can
 * be read in one place instead of a decision scattered across call sites. The circuit breaker sits
 * above it, because the two solve different problems: retries handle a <em>transient</em> failure, a
 * breaker handles a <em>sustained</em> one, and retrying against a service that is down wastes the
 * caller's threads and deadline while adding load to a dependency already failing.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InventoryGrpcProperties.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "app.grpc.client.inventory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class InventoryChannelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InventoryChannelConfiguration.class);

    /** Registry key, and the tag value on every breaker metric. */
    public static final String BREAKER_NAME = "inventory-grpc";

    /**
     * The statuses that count as the dependency failing.
     *
     * <p>The exclusions are the important half, and getting them wrong fails in the worst direction.
     * {@code NOT_FOUND}, {@code INVALID_ARGUMENT} and {@code FAILED_PRECONDITION} are business
     * outcomes — a catalogue full of untracked SKUs would otherwise trip the breaker and cut off a
     * perfectly healthy service because the <em>data</em> was unusual.
     */
    private static final Set<Status.Code> COUNTS_AS_FAILURE = Set.of(
            Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED,
            Status.Code.RESOURCE_EXHAUSTED);

    @Bean(destroyMethod = "shutdown")
    public ManagedChannel inventoryChannel(InventoryGrpcProperties properties,
            DiscoveryClient discovery, GrpcCorrelationClientInterceptor correlation,
            GrpcMetricsClientInterceptor metrics, MeterRegistry meterRegistry) {

        // Registered globally because ManagedChannelBuilder#nameResolverFactory is deprecated and
        // the registry is the supported route. The provider only claims the "consul" scheme, so it
        // cannot affect a channel built for any other target.
        NameResolverRegistry.getDefaultRegistry().register(
                new DiscoveryClientNameResolverProvider(
                        discovery, properties.resolverRefresh().toSeconds()));

        ManagedChannel channel = ManagedChannelBuilder
                .forTarget(DiscoveryClientNameResolverProvider.SCHEME + ":///" + properties.serviceId())
                // Plaintext, as everywhere else in this lab, and every port binds to loopback. A
                // real deployment uses mTLS between services - which gRPC supports natively, and
                // which is the point where a service mesh would take the job over.
                .usePlaintext()
                // Explicitly, and this is not defensive tidiness: the gRPC default is pick_first,
                // which uses one instance and ignores the rest while looking correctly configured.
                .defaultLoadBalancingPolicy("round_robin")
                .keepAliveTime(properties.keepAliveTime().toSeconds(), TimeUnit.SECONDS)
                .keepAliveTimeout(properties.keepAliveTimeout().toSeconds(), TimeUnit.SECONDS)
                // An idle channel must still notice a dead peer, or the first RPC after a quiet
                // period pays the discovery cost.
                .keepAliveWithoutCalls(true)
                .idleTimeout(properties.idleTimeout().toMinutes(), TimeUnit.MINUTES)
                .maxInboundMessageSize(properties.maxInboundMessageSize())
                .enableRetry()
                .defaultServiceConfig(retryServiceConfig(properties))
                .intercept(clientInterceptors(properties, correlation, metrics))
                .build();

        bindChannelState(channel, meterRegistry);
        return channel;
    }

    /**
     * Interceptor order on the client, outermost first.
     *
     * <p>{@code ManagedChannelBuilder#intercept} applies the list so that the first element is
     * closest to the application. Correlation therefore runs before metrics, so a call's metadata is
     * complete before it is timed — and the deadline backstop runs last, so it can see whether any
     * caller upstream of it set one.
     */
    private static List<ClientInterceptor> clientInterceptors(InventoryGrpcProperties properties,
            GrpcCorrelationClientInterceptor correlation, GrpcMetricsClientInterceptor metrics) {
        return List.of(
                correlation,
                metrics,
                new GrpcDeadlineClientInterceptor(properties.batchCheckDeadline()));
    }

    /**
     * gRPC's built-in retry, expressed as service config.
     *
     * <p>Two conditions must both hold before an RPC may be retried: the status is retryable, and
     * the operation is idempotent or carries an idempotency key. Every method on this service
     * satisfies the second — the reads trivially, and {@code ReserveStock} because {@code event_id}
     * was designed in. That key is the same one the Kafka path uses, which is what lets the two
     * paths race without double-reserving. <em>Idempotency is what makes retries possible; without
     * it, a retryable status on a mutating call is a correctness bug.</em>
     *
     * <p>{@code DEADLINE_EXCEEDED} is deliberately absent from the retryable set. The budget is
     * already gone, so retrying guarantees another failure while adding load — and blurring it with
     * {@code UNAVAILABLE} is the classic way a partial degradation becomes a full outage.
     *
     * <p>Retries are bounded by the deadline as well as by the attempt count: when the deadline
     * expires mid-backoff the call fails immediately, which is the property that stops retries
     * turning a slow dependency into an outage.
     */
    private static Map<String, ?> retryServiceConfig(InventoryGrpcProperties properties) {
        Map<String, Object> retryPolicy = Map.of(
                "maxAttempts", (double) properties.maxRetryAttempts(),
                // Must fit inside the deadline: 50 + 100 = 150ms of backoff inside a 300ms budget
                // still leaves room for the attempts themselves.
                "initialBackoff", "0.05s",
                "maxBackoff", "0.5s",
                // Exponential. Linear backoff does not shed load fast enough to help.
                "backoffMultiplier", 2.0D,
                "retryableStatusCodes", List.of("UNAVAILABLE", "RESOURCE_EXHAUSTED", "ABORTED"));

        Map<String, Object> methodConfig = Map.of(
                "name", List.of(Map.of("service", "inventory.v1.InventoryService")),
                "retryPolicy", retryPolicy);

        return Map.of(
                "methodConfig", List.of(methodConfig),
                // The retry budget. A per-attempt policy alone still permits 3x amplification during
                // a partial outage; this caps retries as a fraction of traffic, so they cannot make
                // a struggling dependency worse in precisely the situation they were meant to help.
                "retryThrottling", Map.of(
                        "maxTokens", 100.0D,
                        "tokenRatio", properties.retryBudgetRatio()));
    }

    /**
     * The circuit breaker, built explicitly rather than from {@code resilience4j.*} properties.
     *
     * <p>The decision that matters here — which gRPC statuses count as a failure — cannot be
     * expressed in a status-code-agnostic configuration file, and it is the one most commonly got
     * wrong.
     */
    @Bean
    public CircuitBreakerRegistry inventoryCircuitBreakerRegistry(
            InventoryGrpcProperties properties, MeterRegistry meterRegistry) {

        InventoryGrpcProperties.CircuitBreaker settings = properties.circuitBreaker();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.slidingWindowSize())
                .failureRateThreshold(settings.failureRateThreshold())
                .slowCallRateThreshold(settings.slowCallRateThreshold())
                .slowCallDurationThreshold(settings.slowCallDuration())
                .waitDurationInOpenState(settings.openDuration())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(InventoryChannelConfiguration::countsAsFailure)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        CircuitBreaker breaker = registry.circuitBreaker(BREAKER_NAME);

        // A breaker that opens silently is worse than none: the dependency stops being called and
        // nothing says why. Every transition is a metric, a log line and - at the call site - a
        // span event.
        breaker.getEventPublisher().onStateTransition(event -> log.warn(
                "Circuit breaker '{}' moved {} -> {} (failure rate {}%, slow call rate {}%)",
                event.getCircuitBreakerName(),
                event.getStateTransition().getFromState(),
                event.getStateTransition().getToState(),
                breaker.getMetrics().getFailureRate(),
                breaker.getMetrics().getSlowCallRate()));

        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    public CircuitBreaker inventoryCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(BREAKER_NAME);
    }

    @Bean
    public InventoryServiceGrpc.InventoryServiceBlockingStub inventoryBlockingStub(
            ManagedChannel inventoryChannel) {
        return InventoryServiceGrpc.newBlockingStub(inventoryChannel);
    }

    @Bean
    public InventoryGrpcClient inventoryGrpcClient(
            InventoryServiceGrpc.InventoryServiceBlockingStub stub,
            CircuitBreaker inventoryCircuitBreaker, InventoryGrpcProperties properties) {
        return new InventoryGrpcClient(stub, inventoryCircuitBreaker, properties);
    }

    /** Whether a failure means the dependency is unwell, as opposed to giving an unwelcome answer. */
    private static boolean countsAsFailure(Throwable failure) {
        return failure instanceof StatusRuntimeException status
                && COUNTS_AS_FAILURE.contains(status.getStatus().getCode());
    }

    /**
     * Publishes the channel's connectivity as a gauge per state.
     *
     * <p>{@code TRANSIENT_FAILURE} here is a client that cannot reach the server at all, and it
     * fires before any user notices. It is also the first thing to look at when the client reports
     * {@code UNAVAILABLE} but the server's own dashboards are green.
     */
    private static void bindChannelState(ManagedChannel channel, MeterRegistry registry) {
        for (ConnectivityState state : ConnectivityState.values()) {
            Gauge.builder(com.observability.lab.shared.grpc.GrpcFields.CLIENT_CHANNEL_STATE,
                            channel, c -> c.getState(false) == state ? 1d : 0d)
                    .description("1 when the channel is in this connectivity state")
                    .tags(Tags.of("state", state.name(), "target", channel.authority()))
                    .register(registry);
        }
    }
}
