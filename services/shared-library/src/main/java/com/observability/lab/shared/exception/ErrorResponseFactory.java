package com.observability.lab.shared.exception;

import com.observability.lab.shared.api.ApiError;
import com.observability.lab.shared.api.ApiResponse;
import com.observability.lab.shared.api.FieldViolation;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Builds the error response and writes the log line, in one place.
 *
 * <p>Extracted from {@link GlobalExceptionHandler} so that advice which can only be registered
 * conditionally — {@link ConstraintViolationAdvice}, which needs Bean Validation on the classpath —
 * produces byte-identical responses and log lines. Two advice classes each with their own copy of
 * this logic is two policies that drift.
 */
public final class ErrorResponseFactory {

    private static final Logger log = LoggerFactory.getLogger(ErrorResponseFactory.class);

    /** code, category, message. The correlation fields come from the MDC, not from the message. */
    private static final String FORMAT = "Request failed [{}] category={} - {}";

    private ErrorResponseFactory() {
        throw new AssertionError("No instances.");
    }

    /** Builds a response, deriving the status from the error's category. */
    public static ResponseEntity<ApiResponse<Void>> respond(
            ErrorCode errorCode, String message, List<FieldViolation> violations, Throwable cause) {
        return respond(statusFor(errorCode.category()), errorCode, message, violations, cause);
    }

    /**
     * Builds a response with an explicit status.
     *
     * <p>Used where HTTP is more specific than the domain category: a 405 or a 415 tells the caller
     * more than the flat 400 the {@code VALIDATION} category would produce.
     */
    public static ResponseEntity<ApiResponse<Void>> respond(
            HttpStatus status,
            ErrorCode errorCode,
            String message,
            List<FieldViolation> violations,
            Throwable cause) {

        String logged = message == null || message.isBlank() ? errorCode.defaultMessage() : message;
        logFailure(errorCode, logged, cause);

        // A server fault reveals only the generic message. A client fault may see the specific one,
        // because it describes what the caller did wrong and is what makes the error actionable.
        String exposed = errorCode.category().isServerFault() ? errorCode.defaultMessage() : logged;

        return ResponseEntity.status(status)
                .body(ApiResponse.failure(ApiError.of(errorCode.code(), exposed, violations)));
    }

    private static void logFailure(ErrorCode errorCode, String message, Throwable cause) {
        ErrorCategory category = errorCode.category();
        switch (category.logLevel()) {
            case ERROR -> log.error(FORMAT, errorCode.code(), category, message, cause);
            // No stack trace for a caller's mistake: fifty frames of servlet plumbing explain
            // nothing about a missing field, and the volume buries the failures that do matter.
            case WARN -> log.warn(FORMAT, errorCode.code(), category, message);
            case INFO -> log.info(FORMAT, errorCode.code(), category, message);
            case DEBUG -> log.debug(FORMAT, errorCode.code(), category, message);
            case TRACE -> log.trace(FORMAT, errorCode.code(), category, message);
        }
    }

    /**
     * Maps a failure category onto an HTTP status.
     *
     * <p>The only place the two vocabularies meet, which keeps {@link ErrorCategory} usable from the
     * domain layer without dragging a web dependency into it.
     */
    public static HttpStatus statusFor(ErrorCategory category) {
        return switch (category) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case AUTHENTICATION -> HttpStatus.UNAUTHORIZED;
            case AUTHORIZATION -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            // 422, not 400: the request was well-formed and understood, and was refused by a rule.
            case BUSINESS_RULE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INTEGRATION -> HttpStatus.BAD_GATEWAY;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case TECHNICAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
