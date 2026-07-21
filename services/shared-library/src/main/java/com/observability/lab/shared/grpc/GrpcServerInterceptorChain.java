package com.observability.lab.shared.grpc;

import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.BindableService;
import java.util.List;

/**
 * The platform's server interceptors, in the one order that works.
 *
 * <p>Interceptor order is not a preference here — each position is load-bearing:
 *
 * <ol>
 *   <li><strong>Correlation</strong> outermost, so every interceptor inside it and the handler
 *       itself already have a trace id, a correlation id and a caller. A failure logged before the
 *       context exists is a failure nobody can correlate, and authentication failures are precisely
 *       the ones worth correlating.
 *   <li><strong>Metrics</strong>, so the timer spans the whole of handling rather than the part
 *       after authentication.
 *   <li><strong>Logging</strong>, so the line it writes reports the status the caller actually
 *       received.
 *   <li><strong>Authentication</strong>, inside logging and metrics so a rejected call still appears
 *       in both. An UNAUTHENTICATED that is invisible to the dashboards is how a misconfigured
 *       client goes unnoticed for a week.
 *   <li><strong>Exception mapping</strong> innermost, closest to the handler. Outside the logging
 *       interceptor it would record UNKNOWN for every failure, because gRPC's own fallback would
 *       have run first.
 * </ol>
 *
 * <p>Composition uses {@link ServerInterceptors#interceptForward}, where the first element is the
 * outermost. The similarly named {@code intercept} applies the list in reverse, which is a coin flip
 * nobody should have to remember correctly.
 */
public record GrpcServerInterceptorChain(List<ServerInterceptor> outermostFirst) {

    public GrpcServerInterceptorChain {
        outermostFirst = List.copyOf(outermostFirst);
    }

    /** Wraps a generated service implementation in the whole chain. */
    public ServerServiceDefinition wrap(BindableService service) {
        return ServerInterceptors.interceptForward(service, outermostFirst);
    }

    /** Wraps an already-bound definition, such as the built-in health service. */
    public ServerServiceDefinition wrap(ServerServiceDefinition service) {
        return ServerInterceptors.interceptForward(service, outermostFirst);
    }
}
