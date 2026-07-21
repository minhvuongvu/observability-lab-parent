package com.observability.lab.shared.grpc;

import com.observability.lab.shared.api.FieldViolation;
import com.observability.lab.shared.exception.BaseException;
import com.observability.lab.shared.exception.ErrorCategory;
import com.observability.lab.shared.exception.ValidationException;
import com.google.protobuf.Any;
import com.google.rpc.BadRequest;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.slf4j.event.Level;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;

/**
 * The one place an exception becomes a gRPC status.
 *
 * <p><strong>This mapping is control flow, not documentation.</strong> The retry policy keys off the
 * status code: a database timeout reported as {@code INTERNAL} is not retried and the request fails,
 * while the same timeout reported as {@code UNAVAILABLE} is retried against a healthy instance and
 * succeeds. Getting it wrong is expensive in a way that is invisible in a code review.
 *
 * <p>The mapping is driven by {@link ErrorCategory}, which already drives the HTTP status code and
 * the log level. That is the point: <strong>one exception hierarchy, two transport mappings.</strong>
 * The same refusal is a 422 over REST and {@code FAILED_PRECONDITION} over gRPC, and neither counts
 * as a fault.
 *
 * <p>Handlers never construct a {@link StatusRuntimeException} themselves. One mapping in one place,
 * or the taxonomy drifts per method and the retry policy stops meaning anything.
 *
 * @see <a href="../../../../../../../../../GRPC_ERROR_HANDLING.md">GRPC_ERROR_HANDLING.md</a>
 */
public final class GrpcStatusMapper {

    /**
     * Carries {@link BadRequest} in the status trailers.
     *
     * <p>Structured detail rather than a message string, so a client branches on a field path
     * instead of parsing English.
     */
    private static final Metadata.Key<com.google.rpc.Status> STATUS_DETAILS_KEY =
            ProtoUtils.keyForProto(com.google.rpc.Status.getDefaultInstance());

    private GrpcStatusMapper() {
        throw new AssertionError("No instances.");
    }

    /**
     * Maps a failure to the status the caller sees.
     *
     * <p>Deliberately never leaks internals into the description: no SQL, no stack trace, no
     * hostname. The client gets the status, a generic message and the trace id; the detail is in the
     * logs, behind access control. Same policy as the HTTP error envelope.
     */
    public static Status toStatus(Throwable failure) {
        // Already a status: something below deliberately chose one. Respect it rather than
        // re-deriving a worse answer from the exception type.
        if (failure instanceof StatusRuntimeException statusRuntime) {
            return statusRuntime.getStatus();
        }
        if (failure instanceof StatusException status) {
            return status.getStatus();
        }

        if (failure instanceof BaseException platform) {
            return fromCategory(platform.category())
                    .withDescription(platform.getMessage())
                    .withCause(failure);
        }

        // Spring's data-access hierarchy. These are the ones the taxonomy exists for: a lock
        // conflict and a query timeout are *retryable* conditions of a healthy service, and calling
        // either of them INTERNAL turns a recoverable blip into a failed request plus an alert
        // pointing at the wrong system.
        if (failure instanceof OptimisticLockingFailureException) {
            // A concurrency conflict, not a fault. Two callers reserving the same SKU at once is
            // the normal case in this system, and retrying is exactly the right response.
            return Status.ABORTED.withDescription("concurrent modification").withCause(failure);
        }
        if (failure instanceof QueryTimeoutException
                || failure instanceof CannotAcquireLockException
                || failure instanceof DataAccessResourceFailureException) {
            return Status.UNAVAILABLE
                    .withDescription("the datastore is not answering right now").withCause(failure);
        }

        if (failure instanceof TimeoutException) {
            return Status.DEADLINE_EXCEEDED.withDescription("upstream timed out").withCause(failure);
        }
        if (failure instanceof RejectedExecutionException) {
            // The handler pool refused the work. Retryable with backoff, and shedding load
            // deliberately is not the same thing as being broken.
            return Status.RESOURCE_EXHAUSTED.withDescription("server is shedding load").withCause(failure);
        }
        if (failure instanceof IllegalArgumentException) {
            return Status.INVALID_ARGUMENT.withDescription(failure.getMessage()).withCause(failure);
        }

        // Anything unmapped is a bug in this service, or a gap in this method. Both are INTERNAL,
        // and both deserve the stack trace the logging interceptor will attach.
        return Status.INTERNAL.withDescription("internal error").withCause(failure);
    }

    /**
     * The trailers that accompany the status, carrying structured detail where there is any.
     *
     * <p>Errors go in the status channel, never in a successful response. A {@code status: OK}
     * carrying an {@code error_message} field means every client has to check two places, and one of
     * them will eventually be forgotten.
     */
    public static Metadata toTrailers(Throwable failure) {
        Metadata trailers = new Metadata();
        if (!(failure instanceof ValidationException validation) || validation.violations().isEmpty()) {
            return trailers;
        }

        BadRequest.Builder badRequest = BadRequest.newBuilder();
        for (FieldViolation violation : validation.violations()) {
            badRequest.addFieldViolations(BadRequest.FieldViolation.newBuilder()
                    .setField(violation.field())
                    .setDescription(violation.message()));
        }
        trailers.put(STATUS_DETAILS_KEY, com.google.rpc.Status.newBuilder()
                .setCode(Status.Code.INVALID_ARGUMENT.value())
                .setMessage("request validation failed")
                .addDetails(Any.pack(badRequest.build()))
                .build());
        return trailers;
    }

    /**
     * The gRPC status a platform error category becomes.
     *
     * <p>Row for row, this is the HTTP mapping in {@code GlobalExceptionHandler} expressed in the
     * other transport's vocabulary.
     */
    private static Status fromCategory(ErrorCategory category) {
        return switch (category) {
            // Malformed regardless of system state. The caller must change the request.
            case VALIDATION -> Status.INVALID_ARGUMENT;
            case AUTHENTICATION -> Status.UNAUTHENTICATED;
            case AUTHORIZATION -> Status.PERMISSION_DENIED;
            case NOT_FOUND -> Status.NOT_FOUND;
            case CONFLICT -> Status.ALREADY_EXISTS;
            // Well-formed, but the current state forbids it. Distinct from INVALID_ARGUMENT
            // because the caller's response differs: re-read state and reconsider, rather than
            // fix the request.
            case BUSINESS_RULE -> Status.FAILED_PRECONDITION;
            // A dependency failed. Another instance, or the same one later, may well succeed —
            // which is precisely what makes this UNAVAILABLE rather than INTERNAL.
            case INTEGRATION -> Status.UNAVAILABLE;
            case TIMEOUT -> Status.DEADLINE_EXCEEDED;
            case TECHNICAL -> Status.INTERNAL;
        };
    }

    /**
     * Whether a status is a <em>fault</em> — the service failing, as opposed to answering.
     *
     * <p>This is the definition the error-rate panels and the alert rules use, and the exclusions are
     * the important part. {@code NOT_FOUND} for an untracked SKU is an answer. Counting business
     * outcomes as errors makes the error rate track catalogue quality instead of service health, and
     * an alert built on that is one people mute.
     */
    public static boolean isFault(Status.Code code) {
        return code == Status.Code.INTERNAL
                || code == Status.Code.UNKNOWN
                || code == Status.Code.DATA_LOSS
                || code == Status.Code.UNAVAILABLE;
    }

    /**
     * The level a terminal status is logged at.
     *
     * <p>Mirrors the HTTP policy exactly: a rejected order logs at {@code INFO}, and only genuine
     * faults log at {@code ERROR}. One rule, two protocols — and it is what keeps the alert channel
     * from becoming noise.
     */
    public static Level levelFor(Status.Code code) {
        return switch (code) {
            // Business answers. The system is working.
            case OK, NOT_FOUND, ALREADY_EXISTS, FAILED_PRECONDITION, OUT_OF_RANGE, CANCELLED -> Level.INFO;

            // The caller is wrong, or the server is deliberately shedding load, or the caller's
            // own budget ran out. Actionable, but not by the on-call engineer for this service.
            case INVALID_ARGUMENT, PERMISSION_DENIED, UNAUTHENTICATED, RESOURCE_EXHAUSTED,
                 DEADLINE_EXCEEDED, ABORTED, UNIMPLEMENTED, UNAVAILABLE -> Level.WARN;

            // A bug. The only statuses that deserve a stack trace.
            case INTERNAL, UNKNOWN, DATA_LOSS -> Level.ERROR;
        };
    }
}
