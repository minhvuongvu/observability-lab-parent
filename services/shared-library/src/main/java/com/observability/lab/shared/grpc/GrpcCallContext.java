package com.observability.lab.shared.grpc;

import io.grpc.Context;
import io.grpc.Deadline;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Per-call values, carried in the gRPC {@link Context} rather than in a thread local.
 *
 * <p>The distinction matters. The MDC is thread-bound, and a gRPC call does not stay on one thread:
 * it is accepted on a transport thread, handled on the executor, and a streaming response may be
 * produced from a third thread entirely. The gRPC {@code Context} is designed for that — it is
 * attached around every listener callback and propagates through {@code Context.wrap} — so it is the
 * authoritative carrier here, and the MDC is a mirror of it maintained for the log encoder.
 *
 * @see GrpcCorrelationServerInterceptor which populates these
 */
public final class GrpcCallContext {

    public static final Context.Key<String> CORRELATION_ID = Context.key("correlation_id");
    public static final Context.Key<String> REQUEST_ID = Context.key("request_id");
    public static final Context.Key<String> CALLER_SERVICE = Context.key("caller_service");

    /** Subject of the verified token, when the call carried one. */
    public static final Context.Key<String> USER_ID = Context.key("user_id");

    /** Realm roles from the verified token, already prefixed {@code ROLE_}. */
    public static final Context.Key<java.util.Set<String>> ROLES = Context.key("roles");

    private GrpcCallContext() {
        throw new AssertionError("No instances.");
    }

    public static Optional<String> callerService() {
        return Optional.ofNullable(CALLER_SERVICE.get());
    }

    public static Optional<String> userId() {
        return Optional.ofNullable(USER_ID.get());
    }

    public static boolean hasRole(String role) {
        java.util.Set<String> roles = ROLES.get();
        return roles != null && roles.contains(role);
    }

    /**
     * Whether too little of the caller's budget remains to be worth starting work.
     *
     * <p>This is the capability HTTP has no equivalent for. The caller's deadline travels with the
     * call in {@code grpc-timeout} metadata, so the server can see what is left and decline work
     * nobody will still be waiting for. Under load that is the difference between shedding load and
     * spending a database connection on a result that will be discarded — which is how a slow
     * service becomes an unavailable one.
     *
     * @param minimum the least remaining budget worth starting for
     * @return {@code true} when a deadline is set and less than {@code minimum} remains
     */
    public static boolean budgetExhausted(Duration minimum) {
        Deadline deadline = Context.current().getDeadline();
        return deadline != null
                && deadline.timeRemaining(TimeUnit.MILLISECONDS) < minimum.toMillis();
    }

    /** Remaining budget, or empty when the caller set no deadline. */
    public static Optional<Duration> remainingBudget() {
        Deadline deadline = Context.current().getDeadline();
        return deadline == null
                ? Optional.empty()
                : Optional.of(Duration.ofMillis(deadline.timeRemaining(TimeUnit.MILLISECONDS)));
    }
}
