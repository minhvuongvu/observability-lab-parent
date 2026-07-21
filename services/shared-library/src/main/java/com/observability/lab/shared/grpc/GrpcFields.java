package com.observability.lab.shared.grpc;

/**
 * The gRPC vocabulary: log field names and metric tag keys, which are deliberately the same strings.
 *
 * <p>The governing principle is that <strong>a new protocol must not introduce a new vocabulary</strong>.
 * {@code service}, {@code environment} and {@code version} already tag every metric, log line, span
 * and profile this platform produces; gRPC telemetry reuses all three and adds only what is genuinely
 * gRPC-specific. The payoff is concrete: "is the slowdown in the public API or in the internal hop"
 * becomes a filter on {@code protocol}, not a different dashboard.
 *
 * <p>Names are {@code snake_case} to match every other field in the log schema, and they end up in
 * PromQL and Loki queries, so they are a public contract.
 */
public final class GrpcFields {

    /**
     * Value of {@link com.observability.lab.shared.correlation.CorrelationFields#PROTOCOL} for a
     * gRPC call.
     *
     * <p>That one field is what lets every existing query separate internal traffic from external:
     * {@code {service="inventory-service"} | json | protocol="grpc"}.
     */
    public static final String PROTOCOL_GRPC = "grpc";

    /**
     * Fully qualified service name, <em>including the major version</em> —
     * {@code inventory.v1.InventoryService}.
     *
     * <p>The version being part of this label is what makes a deprecation measurable: v1 can be
     * retired when the call rate for {@code inventory.v1.*} reaches zero, and not before. A
     * deprecation you cannot measure is a deprecation that never finishes.
     */
    public static final String GRPC_SERVICE = "grpc_service";

    /** The RPC, without the service prefix. Bounded by the number of RPCs the service declares. */
    public static final String GRPC_METHOD = "grpc_method";

    /**
     * {@code UNARY}, {@code SERVER_STREAMING}, {@code CLIENT_STREAMING} or {@code BIDI_STREAMING}.
     *
     * <p>Worth its own dimension because it changes what a duration means: 45 minutes is alarming
     * for the first and entirely normal for the third.
     */
    public static final String GRPC_TYPE = "grpc_type";

    /** Terminal status name, e.g. {@code OK}, {@code NOT_FOUND}, {@code UNAVAILABLE}. */
    public static final String GRPC_STATUS = "grpc_status";

    /** Server-side handling time, excluding network. Log field only — the metric is a timer. */
    public static final String GRPC_DURATION_MS = "grpc_duration_ms";

    /** Which service made the call, from {@code x-caller-service} metadata. */
    public static final String CALLER_SERVICE = "caller_service";

    /**
     * Peer address of the caller.
     *
     * <p><strong>A log field, never a metric tag.</strong> In a scaled deployment the set of peer
     * addresses is unbounded, and one time series per peer is how a metrics backend is brought
     * down. Same rule as {@code product_sku} and {@code order_number}.
     */
    public static final String PEER_ADDRESS = "peer_address";

    /**
     * The gRPC-specific MDC keys, so a call can clear precisely what it set.
     *
     * <p>The handler thread goes straight back into gRPC's pool. Without the clear, the next RPC —
     * quite possibly for a different method and a different caller — inherits these values and the
     * logs confidently attribute one call's work to another.
     */
    public static final java.util.List<String> MDC_FIELDS =
            java.util.List.of(GRPC_SERVICE, GRPC_METHOD, GRPC_TYPE, CALLER_SERVICE, PEER_ADDRESS);

    // --- Metric names --------------------------------------------------------

    public static final String SERVER_REQUESTS = "grpc.server.requests";
    public static final String SERVER_REQUEST_DURATION = "grpc.server.request.duration";
    public static final String SERVER_HANDLED = "grpc.server.handled";
    public static final String SERVER_MESSAGES_RECEIVED = "grpc.server.messages.received";
    public static final String SERVER_MESSAGES_SENT = "grpc.server.messages.sent";
    public static final String SERVER_ACTIVE_CALLS = "grpc.server.active.calls";

    public static final String CLIENT_REQUESTS = "grpc.client.requests";
    public static final String CLIENT_REQUEST_DURATION = "grpc.client.request.duration";
    public static final String CLIENT_ACTIVE_CALLS = "grpc.client.active.calls";
    public static final String CLIENT_CHANNEL_STATE = "grpc.client.channel.state";

    /**
     * Name given to the gRPC handler executor when it is bound to Micrometer.
     *
     * <p>Referenced by the {@code GrpcExecutorSaturated} alert. gRPC dispatches onto its own
     * executor, separate from Tomcat's, and a pool exhausted here queues RPCs while the HTTP thread
     * pool sits idle and every JVM metric looks healthy — the failure with no other symptom.
     */
    public static final String EXECUTOR_NAME = "grpc-executor";

    /** Health checks, excluded from logs and traces: pure probe noise, never the trace anyone wants. */
    public static final String HEALTH_SERVICE = "grpc.health.v1.Health";

    private GrpcFields() {
        throw new AssertionError("No instances.");
    }
}
