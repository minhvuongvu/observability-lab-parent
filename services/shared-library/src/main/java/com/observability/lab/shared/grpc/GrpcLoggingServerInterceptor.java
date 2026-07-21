package com.observability.lab.shared.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.concurrent.TimeUnit;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * One log line per RPC, written when the call closes.
 *
 * <p>A gRPC call has a lifecycle — open, message(s), half-close, close — and it is tempting to log
 * each stage. Doing so multiplies volume by four for a unary call and without bound for a streaming
 * one, and buys nothing: every interesting fact is known at close. So: <strong>one line, at
 * close</strong>, carrying the status and the duration.
 *
 * <p>What is deliberately <em>not</em> logged:
 *
 * <ul>
 *   <li><strong>Request and response messages.</strong> Protobuf payloads carry customer
 *       identifiers, basket contents and quantities. A log store is not an access-controlled
 *       datastore, and "log the request on error" is how personal data reaches a search index.
 *   <li><strong>Metadata wholesale.</strong> {@code authorization} is in there; dumping the metadata
 *       map logs a usable bearer token with a multi-day retention.
 *   <li><strong>Stack traces for expected statuses.</strong> {@code NOT_FOUND} for an untracked SKU
 *       is an answer, not a fault. A stack trace makes ordinary operation look like a bug and buries
 *       the real ones.
 * </ul>
 *
 * <p>Health checks are skipped entirely. On a service probed every ten seconds by three checkers
 * they are the single largest log producer, and every line of it is noise.
 */
public class GrpcLoggingServerInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcLoggingServerInterceptor.class);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        GrpcMethodRef method = GrpcMethodRef.of(call.getMethodDescriptor());
        if (method.isHealthCheck()) {
            return next.startCall(call, headers);
        }

        long startedAt = System.nanoTime();

        ServerCall<ReqT, RespT> logging = new ForwardingServerCall
                .SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                write(method, status, durationMs);
                super.close(status, trailers);
            }
        };

        return next.startCall(logging, headers);
    }

    /**
     * Writes the closing line at the level the status deserves.
     *
     * <p>The grpc identity fields are already in the MDC for the whole call — put there by
     * {@link GrpcCorrelationServerInterceptor} — so only what is known at close is added here, and
     * it is added as structured arguments rather than MDC entries so {@code grpc_duration_ms}
     * arrives in the record as a number rather than a quoted string.
     */
    private static void write(GrpcMethodRef method, Status status, long durationMs) {
        Level level = GrpcStatusMapper.levelFor(status.getCode());
        String message = "{} completed";

        Object[] arguments = {
            method.method(),
            StructuredArguments.keyValue(GrpcFields.GRPC_STATUS, status.getCode().name()),
            StructuredArguments.keyValue(GrpcFields.GRPC_DURATION_MS, durationMs),
        };

        switch (level) {
            case ERROR -> {
                // The only statuses that earn a stack trace. status.getCause() is populated by
                // GrpcStatusMapper; a null cause here means something closed the call directly,
                // and there is no trace to attach.
                if (status.getCause() != null) {
                    log.error(message, append(arguments, status.getCause()));
                } else {
                    log.error(message, arguments);
                }
            }
            case WARN -> log.warn(message, arguments);
            default -> log.info(message, arguments);
        }
    }

    private static Object[] append(Object[] arguments, Throwable cause) {
        Object[] extended = new Object[arguments.length + 1];
        System.arraycopy(arguments, 0, extended, 0, arguments.length);
        extended[arguments.length] = cause;
        return extended;
    }
}
