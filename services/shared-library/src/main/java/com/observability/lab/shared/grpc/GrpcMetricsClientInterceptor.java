package com.observability.lab.shared.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * What the caller actually experienced: duration including network, queueing and connection setup.
 *
 * <p><strong>Client and server duration are both needed, and they are different numbers.</strong>
 * The gap between them is transport, queueing and connection establishment. A client p99 of 400 ms
 * against a server p99 of 15 ms is not a slow service — it is a saturated channel or a connection
 * storm, and only having both distinguishes them. Measuring one and assuming the other is how a
 * channel problem gets investigated as a database problem.
 *
 * <p>Also writes the client's own line for a call that failed. Successful calls are not logged here:
 * the server already writes one line per call, and a second from the client doubles the volume to
 * say the same thing. A failure is different — the client may be the only side that saw it, which is
 * exactly the case when the server was never reached.
 */
public class GrpcMetricsClientInterceptor implements ClientInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcMetricsClientInterceptor.class);

    private final MeterRegistry registry;
    private final Map<Tags, AtomicInteger> activeCalls = new ConcurrentHashMap<>();

    public GrpcMetricsClientInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

        GrpcMethodRef ref = GrpcMethodRef.of(method);
        Tags methodTags = ref.tags();
        AtomicInteger inFlight = active(methodTags);

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                Timer.Sample sample = Timer.start(registry);
                inFlight.incrementAndGet();

                super.start(new ForwardingClientCallListener
                        .SimpleForwardingClientCallListener<>(responseListener) {
                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        inFlight.decrementAndGet();
                        record(methodTags, status, sample);
                        if (GrpcStatusMapper.levelFor(status.getCode()) != Level.INFO) {
                            logFailure(ref, status);
                        }
                        super.onClose(status, trailers);
                    }
                }, headers);
            }
        };
    }

    private void record(Tags methodTags, Status status, Timer.Sample sample) {
        Tags withStatus = methodTags.and(GrpcFields.GRPC_STATUS, status.getCode().name());

        sample.stop(Timer.builder(GrpcFields.CLIENT_REQUEST_DURATION)
                .description("Round-trip time per RPC as the caller experienced it, "
                        + "including network, queueing and connection establishment")
                .tags(withStatus)
                .publishPercentileHistogram()
                .register(registry));

        Counter.builder(GrpcFields.CLIENT_REQUESTS)
                .description("RPCs issued, by method and terminal status")
                .tags(withStatus)
                .register(registry)
                .increment();
    }

    private static void logFailure(GrpcMethodRef ref, Status status) {
        Object[] arguments = {
            ref.service() + "/" + ref.method(),
            StructuredArguments.keyValue(GrpcFields.GRPC_STATUS, status.getCode().name()),
            StructuredArguments.keyValue("grpc_description", status.getDescription()),
        };
        // WARN, not ERROR, even for UNAVAILABLE: a dependency being down is not this service
        // failing, and putting it at ERROR would make the caller's error rate track the callee's
        // availability. The alert for that belongs on the callee.
        log.warn("{} did not complete", arguments);
    }

    private AtomicInteger active(Tags tags) {
        return activeCalls.computeIfAbsent(tags, key -> {
            AtomicInteger counter = new AtomicInteger();
            Gauge.builder(GrpcFields.CLIENT_ACTIVE_CALLS, counter, AtomicInteger::get)
                    .description("RPCs in flight from this client, per method")
                    .tags(key)
                    .register(registry);
            return counter;
        });
    }
}
