package com.observability.lab.shared.exception;

import com.observability.lab.shared.api.ApiResponse;
import com.observability.lab.shared.api.FieldViolation;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception that escapes a controller into the standard {@link ApiResponse} envelope.
 *
 * <p>Two policies are enforced here and nowhere else, which is the point of having one handler
 * rather than scattered try/catch:
 *
 * <ol>
 *   <li><strong>Status and log level come from {@link ErrorCategory}.</strong> Adding a new failure
 *       mode means declaring an {@link ErrorCode}, not editing this class.
 *   <li><strong>Server faults never return their message to the caller.</strong> A connection
 *       string or an internal hostname in an exception message becomes a 500 body and then a
 *       client-side log entry. The caller gets the code and the trace id; the detail stays in the
 *       logs, one query away.
 * </ol>
 *
 * <p>This class references only Spring MVC types, never {@code jakarta.validation}. That is
 * deliberate: Bean Validation is an optional dependency, and a handler that could not load without
 * it would silently fail to register on a service that does not use it — leaving that service with
 * correlation ids but the framework's default whitelabel error body. Validation-specific handling
 * lives in {@link ConstraintViolationAdvice}, which is registered separately when the classpath
 * allows it.
 *
 * <p>Registered at lowest precedence so a service can add its own advice and have it take priority.
 *
 * @see <a href="../../../../../../../../../docs/SystemDesign.md">docs/SystemDesign.md, section 6.1</a>
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    // --- Platform exceptions ------------------------------------------------

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponse<Void>> handlePlatformException(BaseException exception) {
        List<FieldViolation> violations =
                exception instanceof ValidationException validation ? validation.violations() : null;
        return ErrorResponseFactory.respond(
                ErrorResponseFactory.statusFor(exception.category()),
                exception.errorCode(),
                exception.getMessage(),
                violations,
                exception);
    }

    // --- Request binding ----------------------------------------------------

    /** Raised when a {@code @Valid} request body fails validation. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidBody(MethodArgumentNotValidException exception) {
        Stream<FieldViolation> fieldViolations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()));
        // Class-level constraints report against the object rather than a field, and would
        // otherwise be dropped from the response entirely.
        Stream<FieldViolation> objectViolations = exception.getBindingResult().getGlobalErrors().stream()
                .map(error -> new FieldViolation(error.getObjectName(), error.getDefaultMessage()));

        return ErrorResponseFactory.respond(
                PlatformErrorCode.VALIDATION_FAILED,
                PlatformErrorCode.VALIDATION_FAILED.defaultMessage(),
                Stream.concat(fieldViolations, objectViolations).toList(),
                exception);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        // The parser message can quote the offending payload, so it is logged but not returned.
        return ErrorResponseFactory.respond(
                PlatformErrorCode.MALFORMED_REQUEST,
                PlatformErrorCode.MALFORMED_REQUEST.defaultMessage(),
                null,
                exception);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        String required = exception.getRequiredType() == null
                ? "the expected type"
                : exception.getRequiredType().getSimpleName();
        return ErrorResponseFactory.respond(
                PlatformErrorCode.TYPE_MISMATCH,
                PlatformErrorCode.TYPE_MISMATCH.defaultMessage(),
                List.of(new FieldViolation(exception.getName(), "Expected " + required + ".")),
                exception);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException exception) {
        return ErrorResponseFactory.respond(
                PlatformErrorCode.MISSING_PARAMETER,
                PlatformErrorCode.MISSING_PARAMETER.defaultMessage(),
                List.of(new FieldViolation(exception.getParameterName(), "This parameter is required.")),
                exception);
    }

    // --- Protocol-level failures -------------------------------------------

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception) {
        return ErrorResponseFactory.respond(
                HttpStatus.METHOD_NOT_ALLOWED,
                PlatformErrorCode.METHOD_NOT_SUPPORTED,
                PlatformErrorCode.METHOD_NOT_SUPPORTED.defaultMessage(),
                null,
                exception);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception) {
        return ErrorResponseFactory.respond(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                PlatformErrorCode.MEDIA_TYPE_NOT_SUPPORTED,
                PlatformErrorCode.MEDIA_TYPE_NOT_SUPPORTED.defaultMessage(),
                null,
                exception);
    }

    /**
     * No handler matched the path.
     *
     * <p>Handled explicitly so a request for a missing static resource does not fall through to the
     * catch-all and get logged as an internal error, which would turn a stray favicon request into
     * an {@code ERROR} and pollute the metric every alert is built on.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException exception) {
        return ErrorResponseFactory.respond(
                PlatformErrorCode.RESOURCE_NOT_FOUND,
                PlatformErrorCode.RESOURCE_NOT_FOUND.defaultMessage(),
                null,
                exception);
    }

    // --- Catch-all ----------------------------------------------------------

    /**
     * Anything not matched above.
     *
     * <p>Always an internal error with a stack trace: by definition nobody anticipated it, so the
     * trace is the only evidence there will be.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        return ErrorResponseFactory.respond(
                PlatformErrorCode.INTERNAL_ERROR, exception.getMessage(), null, exception);
    }
}
