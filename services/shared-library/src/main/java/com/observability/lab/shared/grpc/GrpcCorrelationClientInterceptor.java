package com.observability.lab.shared.grpc;

import com.observability.lab.shared.correlation.CorrelationContext;
import com.observability.lab.shared.correlation.ServiceIdentity;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Carries the caller's identity onto the outbound RPC.
 *
 * <p>The gRPC counterpart of the Feign correlation and token-relay interceptors, and the metadata it
 * writes is the same set of names those set as HTTP headers — because gRPC metadata <em>is</em> the
 * HTTP/2 header set. Only the framing differs.
 *
 * <p>What is deliberately <strong>not</strong> written here is {@code traceparent}. The
 * OpenTelemetry agent already instruments {@code grpc-java} and injects it, and a hand-written
 * traceparent would either duplicate the agent's or, worse, replace it with a span id this process
 * invented — which breaks the parent/child link at exactly the hop the trace exists to explain.
 *
 * <p>Metadata is <em>appended to</em>, never replaced. Replacing the map is the single most common
 * way a custom interceptor silently disables trace propagation, and the symptom — a trace that stops
 * at the gRPC boundary — looks like an agent problem rather than an application one.
 */
public class GrpcCorrelationClientInterceptor implements ClientInterceptor {

    private final ServiceIdentity identity;

    public GrpcCorrelationClientInterceptor(ServiceIdentity identity) {
        this.identity = identity;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<>(
                next.newCall(method, callOptions)) {

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                put(headers, GrpcMetadataKeys.REQUEST_ID, CorrelationContext.requestId());
                put(headers, GrpcMetadataKeys.CORRELATION_ID, CorrelationContext.correlationId());

                // Who is calling. With client-side load balancing and one long-lived connection per
                // caller, the provider cannot infer this from the network path - so it is stated.
                put(headers, GrpcMetadataKeys.CALLER_SERVICE, identity.name());

                relayBearerToken(headers);

                super.start(new ForwardingClientCallListener
                        .SimpleForwardingClientCallListener<>(responseListener) {}, headers);
            }
        };
    }

    /**
     * Forwards the end user's token, unchanged.
     *
     * <p>The encoded form, not a re-minted one: this service is not an issuer and must not behave
     * like one. Relaying rather than using a service account preserves <em>who is asking</em>, so
     * the provider can authorise per user instead of only ever seeing "the Order Service" — the same
     * trade the Feign client already makes.
     *
     * <p>Nothing is sent when there is no bearer token in play. A call made from a Kafka listener or
     * a scheduled task has no user, and inventing one would be worse than an UNAUTHENTICATED.
     */
    private static void relayBearerToken(Metadata headers) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            headers.put(GrpcMetadataKeys.AUTHORIZATION, "Bearer " + jwt.getToken().getTokenValue());
        }
    }

    private static void put(Metadata headers, Metadata.Key<String> key, String value) {
        if (value != null && !value.isBlank()) {
            headers.put(key, value);
        }
    }
}
