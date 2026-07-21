package com.observability.lab.shared.grpc;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns an exception escaping a handler into the status the taxonomy says it is.
 *
 * <p>Without this, gRPC's own safety net closes the call with {@code UNKNOWN} and no description.
 * Every {@code UNKNOWN} that reaches a client is therefore a defect in the mapping rather than a
 * category of failure — which is exactly how the status is treated in
 * {@link GrpcStatusMapper#levelFor}.
 *
 * <p>The innermost interceptor, so it sits closest to the handler and the mapped status is what the
 * logging and metrics interceptors outside it observe. If it ran outside them they would record
 * {@code UNKNOWN} for every failure and the status breakdown would be useless.
 */
public class GrpcExceptionServerInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        // gRPC tolerates a double close by logging it, but the log line is confusing and the second
        // status is discarded. Tracking it here keeps the failure path quiet.
        AtomicBoolean closed = new AtomicBoolean(false);

        ServerCall<ReqT, RespT> guarded = new io.grpc.ForwardingServerCall
                .SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                closed.set(true);
                super.close(status, trailers);
            }
        };

        ServerCall.Listener<ReqT> delegate;
        try {
            delegate = next.startCall(guarded, headers);
        } catch (RuntimeException failure) {
            // A handler can refuse before it has read a single message - a client-streaming method
            // checking authorisation as the stream opens is the case here, and refusing then rather
            // than after ten thousand records is the whole point of doing it early. That throw
            // happens inside startCall, not inside a listener callback, so without this arm it
            // escapes to gRPC's own fallback and the caller is told UNKNOWN instead of
            // PERMISSION_DENIED.
            closed.set(true);
            call.close(GrpcStatusMapper.toStatus(failure), GrpcStatusMapper.toTrailers(failure));
            return new ServerCall.Listener<>() {};
        }

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                guard(() -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                // The callback that invokes a unary handler, and therefore the one that throws.
                guard(super::onHalfClose);
            }

            @Override
            public void onReady() {
                guard(super::onReady);
            }

            @Override
            public void onCancel() {
                guard(super::onCancel);
            }

            @Override
            public void onComplete() {
                guard(super::onComplete);
            }

            private void guard(Runnable callback) {
                try {
                    callback.run();
                } catch (RuntimeException failure) {
                    if (closed.compareAndSet(false, true)) {
                        // Swallowed rather than rethrown: the call is already answered, and letting
                        // it propagate would have gRPC close it a second time with UNKNOWN,
                        // overwriting the status just chosen.
                        guarded.close(
                                GrpcStatusMapper.toStatus(failure),
                                GrpcStatusMapper.toTrailers(failure));
                    }
                }
            }
        };
    }
}
