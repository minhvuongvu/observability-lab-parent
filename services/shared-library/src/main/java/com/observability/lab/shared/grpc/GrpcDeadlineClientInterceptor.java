package com.observability.lab.shared.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a default deadline to any call that was issued without one.
 *
 * <p><strong>A gRPC call with no deadline is a defect.</strong> It holds a thread until the
 * connection itself dies, which under a partial network failure can be minutes — and the pool it is
 * holding a thread from is the one every other request needs.
 *
 * <p>A deadline is not a timeout. A timeout is local ("I will wait 300 ms"); a deadline is absolute
 * and <em>propagates</em> ("this call must complete by 10:00:00.300"). It travels in
 * {@code grpc-timeout} metadata, so each hop sees what remains rather than starting its own fresh
 * budget, and a server can decline work whose caller has already given up. That is the property
 * HTTP has no equivalent for.
 *
 * <p>This is a backstop, not the policy. Each call site sets the deadline that suits its operation —
 * 200 ms for a cache-backed point read, five minutes for a bulk reconciliation — because a single
 * number cannot be right for both. What this guarantees is that forgetting to set one is bounded
 * rather than unbounded.
 *
 * <p>Streaming calls are left alone. A deadline on a long-lived subscription would kill it on
 * schedule; those are bounded by keepalive instead.
 */
public class GrpcDeadlineClientInterceptor implements ClientInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcDeadlineClientInterceptor.class);

    private final Duration defaultDeadline;

    public GrpcDeadlineClientInterceptor(Duration defaultDeadline) {
        this.defaultDeadline = defaultDeadline;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

        if (callOptions.getDeadline() != null
                || method.getType() == MethodDescriptor.MethodType.SERVER_STREAMING
                || method.getType() == MethodDescriptor.MethodType.BIDI_STREAMING) {
            return next.newCall(method, callOptions);
        }

        // DEBUG, not WARN: on a correctly configured client this never fires, and on a
        // misconfigured one it would fire on every single call.
        log.debug("No deadline set for {}; applying the {}ms default",
                method.getFullMethodName(), defaultDeadline.toMillis());

        return next.newCall(method,
                callOptions.withDeadlineAfter(defaultDeadline.toMillis(), TimeUnit.MILLISECONDS));
    }
}
