package com.observability.lab.shared.grpc;

import com.observability.lab.shared.correlation.CorrelationContext;
import com.observability.lab.shared.correlation.CorrelationFields;
import com.observability.lab.shared.correlation.ServiceIdentity;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.net.SocketAddress;
import org.slf4j.MDC;

/**
 * Establishes the correlation context for every inbound RPC, and tears it down afterwards.
 *
 * <p>The gRPC counterpart of {@link com.observability.lab.shared.correlation.CorrelationFilter}, and
 * it exists for the same reason: without it the correlation chain breaks at the process boundary and
 * the provider logs the same work under a different identifier — precisely where a distributed trace
 * is most needed.
 *
 * <p>Two carriers, deliberately:
 *
 * <ul>
 *   <li>The gRPC {@link Context} is authoritative. It is attached around every listener callback and
 *       survives the call moving between the transport thread and the handler executor, which a
 *       thread local does not.
 *   <li>The MDC is a mirror, maintained around each callback because Logback reads the MDC and
 *       nothing else. Mirroring the whole call — not only the interceptor's own log line — is what
 *       puts {@code grpc_method} and {@code caller_service} on the <em>application's</em> log lines,
 *       which is where an investigation actually looks.
 * </ul>
 *
 * <p>Must be the outermost interceptor, so everything inside it — metrics, logging, authentication,
 * the handler — already has a populated context.
 */
public class GrpcCorrelationServerInterceptor implements ServerInterceptor {

    private final ServiceIdentity identity;

    public GrpcCorrelationServerInterceptor(ServiceIdentity identity) {
        this.identity = identity;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String requestId = CorrelationContext.sanitise(headers.get(GrpcMetadataKeys.REQUEST_ID));
        if (requestId == null) {
            requestId = CorrelationContext.newId();
        }

        // Trace identity comes from the active span when the OpenTelemetry agent is attached: by the
        // time this interceptor runs the agent has already started the SERVER span for this RPC, and
        // that span - not the caller's - is what a log line for this call belongs to. Reading
        // traceparent from the metadata instead would stamp the caller's span id on every line and
        // quietly break the link between a log and its trace.
        String traceId = null;
        String spanId = null;
        SpanContext current = Span.current().getSpanContext();
        if (current.isValid()) {
            traceId = current.getTraceId();
            spanId = current.getSpanId();
        }

        // Correlation survives across hops, so a caller-supplied value always wins. Falling back to
        // the trace id keeps a business transaction and its trace addressable by one identifier.
        String correlationId = CorrelationContext.sanitise(headers.get(GrpcMetadataKeys.CORRELATION_ID));
        if (correlationId == null) {
            correlationId = traceId != null ? traceId : requestId;
        }

        String callerService = CorrelationContext.sanitise(headers.get(GrpcMetadataKeys.CALLER_SERVICE));

        Context context = Context.current()
                .withValue(GrpcCallContext.REQUEST_ID, requestId)
                .withValue(GrpcCallContext.CORRELATION_ID, correlationId)
                .withValue(GrpcCallContext.CALLER_SERVICE, callerService);

        // Contexts.interceptCall attaches the context around next.startCall as well as around every
        // subsequent callback, so interceptors nested inside this one can read these values during
        // their own interceptCall.
        ServerCall.Listener<ReqT> delegate = Contexts.interceptCall(context, call, headers, next);

        CallIdentity identityOfCall = new CallIdentity(requestId, correlationId, traceId, spanId,
                callerService, peerAddress(call), GrpcMethodRef.of(call.getMethodDescriptor()),
                identity);

        return new MdcMirroringListener<>(delegate, identityOfCall);
    }

    private static String peerAddress(ServerCall<?, ?> call) {
        SocketAddress remote = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (remote == null) {
            return null;
        }
        String rendered = remote.toString();
        // InetSocketAddress renders as "/10.0.2.167:54321"; the leading slash is an artefact of
        // Java's toString, not part of the address.
        return rendered.startsWith("/") ? rendered.substring(1) : rendered;
    }

    /** Everything the MDC needs, resolved once when the call opens. */
    private record CallIdentity(String requestId, String correlationId, String traceId, String spanId,
            String callerService, String peerAddress, GrpcMethodRef method, ServiceIdentity identity) {

        void populateMdc() {
            CorrelationContext.requestId(requestId);
            CorrelationContext.correlationId(correlationId);
            CorrelationContext.traceId(traceId);
            CorrelationContext.spanId(spanId);
            // Written by the authentication interceptor once the token is verified, and read back
            // from the gRPC context here so a callback on a fresh thread still carries the subject.
            CorrelationContext.userId(GrpcCallContext.USER_ID.get());

            CorrelationContext.put(CorrelationFields.SERVICE, identity.name());
            CorrelationContext.put(CorrelationFields.ENVIRONMENT, identity.environment());
            CorrelationContext.put(CorrelationFields.VERSION, identity.version());
            CorrelationContext.put(CorrelationFields.PROTOCOL, GrpcFields.PROTOCOL_GRPC);

            CorrelationContext.put(GrpcFields.GRPC_SERVICE, method.service());
            CorrelationContext.put(GrpcFields.GRPC_METHOD, method.method());
            CorrelationContext.put(GrpcFields.GRPC_TYPE, method.type().name());
            CorrelationContext.put(GrpcFields.CALLER_SERVICE, callerService);
            CorrelationContext.put(GrpcFields.PEER_ADDRESS, peerAddress);
        }

        void clearMdc() {
            CorrelationContext.clear();
            GrpcFields.MDC_FIELDS.forEach(MDC::remove);
        }
    }

    /**
     * Copies the call's identity into the MDC around every listener callback.
     *
     * <p>Around <em>every</em> callback rather than once at the start, because gRPC does not promise
     * that the callbacks of one call run on the same thread, and a streaming call is guaranteed not
     * to. Populating the MDC once at {@code onMessage} would leave {@code onComplete} unattributed
     * on a different thread — which, for a stream, is the only line that says how it ended.
     */
    private static final class MdcMirroringListener<ReqT>
            extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {

        private final CallIdentity call;

        private MdcMirroringListener(ServerCall.Listener<ReqT> delegate, CallIdentity call) {
            super(delegate);
            this.call = call;
        }

        @Override
        public void onMessage(ReqT message) {
            around(() -> super.onMessage(message));
        }

        @Override
        public void onHalfClose() {
            around(super::onHalfClose);
        }

        @Override
        public void onCancel() {
            around(super::onCancel);
        }

        @Override
        public void onComplete() {
            around(super::onComplete);
        }

        @Override
        public void onReady() {
            around(super::onReady);
        }

        private void around(Runnable callback) {
            call.populateMdc();
            try {
                callback.run();
            } finally {
                call.clearMdc();
            }
        }
    }
}
