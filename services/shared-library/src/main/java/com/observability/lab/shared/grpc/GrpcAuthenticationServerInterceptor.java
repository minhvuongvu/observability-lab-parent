package com.observability.lab.shared.grpc;

import com.observability.lab.shared.correlation.CorrelationContext;
import com.observability.lab.shared.security.KeycloakRealmRoleConverter;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Verifies the bearer token relayed in {@code authorization} metadata.
 *
 * <p>The same defence-in-depth argument the HTTP resource server makes, applied to the second
 * transport. The gRPC port is internal and bound to loopback, which is a real control — and it is
 * exactly the kind of control that stops being true the first time the service is containerised by
 * someone who did not read this comment. A port that authenticates does not depend on the network
 * staying the shape it is today.
 *
 * <p>Uses the very same {@link JwtDecoder} as the HTTP path, so issuer, expiry and signature are
 * checked identically on both. Two verifiers with two configurations is how one of them ends up
 * accepting a token the other rejects.
 *
 * <p>The identity is published into the gRPC {@link Context} rather than into Spring Security's
 * {@code SecurityContextHolder}: the holder is thread-bound, and a gRPC call does not stay on one
 * thread.
 */
public class GrpcAuthenticationServerInterceptor implements ServerInterceptor {

    private static final String BEARER = "Bearer ";

    private final JwtDecoder decoder;
    private final KeycloakRealmRoleConverter roles = new KeycloakRealmRoleConverter();

    public GrpcAuthenticationServerInterceptor(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        // Health probes present no token by design: Consul and the container runtime have no user
        // to act as, and demanding one would make the readiness check fail permanently.
        if (GrpcMethodRef.of(call.getMethodDescriptor()).isHealthCheck()) {
            return next.startCall(call, headers);
        }

        String authorization = headers.get(GrpcMetadataKeys.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return deny(call, Status.UNAUTHENTICATED.withDescription("a bearer token is required"));
        }

        Jwt token;
        try {
            token = decoder.decode(authorization.substring(BEARER.length()).trim());
        } catch (JwtException invalid) {
            // The reason is deliberately not echoed to the caller: "signature mismatch" and
            // "expired" are useful to an attacker probing which of the two they have. It is in the
            // log line, behind access control.
            return deny(call, Status.UNAUTHENTICATED
                    .withDescription("the bearer token was not accepted")
                    .withCause(invalid));
        }

        Set<String> authorities = roles.convert(token).stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());

        String subject = token.getClaimAsString("preferred_username");
        if (subject == null) {
            subject = token.getSubject();
        }

        Context context = Context.current()
                .withValue(GrpcCallContext.USER_ID, subject)
                .withValue(GrpcCallContext.ROLES, authorities);

        // Mirrored immediately so the rest of *this* callback logs with the subject; the correlation
        // interceptor reads it back from the context for every later callback.
        CorrelationContext.userId(subject);

        return Contexts.interceptCall(context, call, headers, next);
    }

    /**
     * Closes the call and returns a listener that ignores everything that follows.
     *
     * <p>Refusing here rather than throwing keeps the status precise: an exception would be mapped
     * by {@link GrpcExceptionServerInterceptor}, which sits <em>inside</em> this one and would never
     * see it.
     */
    private static <ReqT, RespT> ServerCall.Listener<ReqT> deny(
            ServerCall<ReqT, RespT> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<>() {};
    }
}
