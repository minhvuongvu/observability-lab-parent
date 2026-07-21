package com.observability.lab.shared.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RED and USE signals for the gRPC server, in the platform's existing vocabulary.
 *
 * <p>The common tags — {@code service}, {@code environment}, {@code version} — are applied by
 * {@link com.observability.lab.shared.autoconfigure.MetricsAutoConfiguration} at the registry, so
 * nothing here has to remember them and a meter registered by a library carries them too.
 *
 * <p><strong>Cardinality.</strong> {@code grpc_method} has as many values as the service has RPCs;
 * {@code grpc_status} has seventeen; {@code caller_service} has as many values as there are callers.
 * All bounded. What is deliberately absent is {@code peer_address} — logged, never tagged, because
 * in a scaled deployment it is unbounded — along with every business identifier.
 */
public class GrpcMetricsServerInterceptor implements ServerInterceptor {

    private final MeterRegistry registry;

    /**
     * Gauges are stateful, so the backing counter has to be found again on every call rather than
     * re-registered. Bounded by the number of (service, method, type) combinations, which is the
     * number of RPCs the server declares.
     */
    private final Map<Tags, AtomicInteger> activeCalls = new ConcurrentHashMap<>();

    public GrpcMetricsServerInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        GrpcMethodRef method = GrpcMethodRef.of(call.getMethodDescriptor());
        if (method.isHealthCheck()) {
            return next.startCall(call, headers);
        }

        String caller = orUnknown(GrpcCallContext.CALLER_SERVICE.get());
        Tags methodTags = method.tags();
        Timer.Sample sample = Timer.start(registry);

        AtomicInteger inFlight = active(methodTags);
        inFlight.incrementAndGet();

        Counter sent = counter(GrpcFields.SERVER_MESSAGES_SENT, methodTags,
                "Response messages written, by method. Streaming throughput outbound.");
        Counter received = counter(GrpcFields.SERVER_MESSAGES_RECEIVED, methodTags,
                "Request messages read, by method. Streaming throughput inbound.");

        ServerCall<ReqT, RespT> measured = new ForwardingServerCall
                .SimpleForwardingServerCall<>(call) {
            @Override
            public void sendMessage(RespT message) {
                // Counted, not traced. A stream carrying ten thousand messages should produce a
                // handful of spans and one counter that reads ten thousand - not ten thousand spans
                // that overwhelm the backend and answer nothing the counter would not.
                sent.increment();
                super.sendMessage(message);
            }

            @Override
            public void close(Status status, Metadata trailers) {
                inFlight.decrementAndGet();
                record(methodTags, caller, status, sample);
                super.close(status, trailers);
            }
        };

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                next.startCall(measured, headers)) {
            @Override
            public void onMessage(ReqT message) {
                received.increment();
                super.onMessage(message);
            }
        };
    }

    private void record(Tags methodTags, String caller, Status status, Timer.Sample sample) {
        Tags withStatus = methodTags.and(GrpcFields.GRPC_STATUS, status.getCode().name());

        sample.stop(Timer.builder(GrpcFields.SERVER_REQUEST_DURATION)
                .description("Server-side handling time per RPC, excluding network")
                .tags(withStatus)
                // A histogram, because the deadline is a hard boundary: an RPC either completes
                // inside it or returns DEADLINE_EXCEEDED, and knowing what fraction of calls land
                // in the bucket just below the deadline is how a timeout is seen coming before it
                // starts firing. A mean cannot express that, and a pre-computed percentile cannot
                // be aggregated across instances.
                .publishPercentileHistogram()
                .register(registry));

        counter(GrpcFields.SERVER_REQUESTS, withStatus.and(GrpcFields.CALLER_SERVICE, caller),
                "RPCs handled, by method, status and caller").increment();

        counter(GrpcFields.SERVER_HANDLED, withStatus,
                "Terminal status per call").increment();
    }

    private AtomicInteger active(Tags tags) {
        return activeCalls.computeIfAbsent(tags, key -> {
            AtomicInteger counter = new AtomicInteger();
            Gauge.builder(GrpcFields.SERVER_ACTIVE_CALLS, counter, AtomicInteger::get)
                    .description("RPCs in flight. Rising with flat throughput means calls are "
                            + "taking longer; for SERVER_STREAMING it is the number of held "
                            + "subscriptions, which leak silently when a client goes away badly.")
                    .tags(key)
                    .register(registry);
            return counter;
        });
    }

    private Counter counter(String name, Tags tags, String description) {
        return Counter.builder(name).description(description).tags(tags).register(registry);
    }

    private static String orUnknown(String value) {
        // A tag value of null is dropped by some registries and rendered as "null" by others.
        // "unknown" is a value you can see on a dashboard and go and fix.
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
