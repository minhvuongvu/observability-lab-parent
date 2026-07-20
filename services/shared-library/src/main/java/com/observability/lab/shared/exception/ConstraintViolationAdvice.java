package com.observability.lab.shared.exception;

import com.observability.lab.shared.api.ApiResponse;
import com.observability.lab.shared.api.FieldViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates Bean Validation failures raised outside request-body binding.
 *
 * <p>Separate from {@link GlobalExceptionHandler} because it is the only advice that references
 * {@code jakarta.validation}, which is an optional dependency of this library. Keeping it apart
 * means a service without Bean Validation still gets the full error envelope for everything else,
 * rather than losing the handler entirely to a failed class-loading condition.
 *
 * <p>Covers {@code @Validated} method parameters, path variables and query parameters.
 * {@code @Valid} request bodies raise {@code MethodArgumentNotValidException} instead, which the
 * main handler owns.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ConstraintViolationAdvice {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException exception) {

        List<FieldViolation> violations = exception.getConstraintViolations().stream()
                .map(violation ->
                        new FieldViolation(violation.getPropertyPath().toString(), violation.getMessage()))
                // The exception carries an unordered Set, so without this the same invalid request
                // returns its violations in a different order each time.
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();

        return ErrorResponseFactory.respond(
                PlatformErrorCode.VALIDATION_FAILED,
                PlatformErrorCode.VALIDATION_FAILED.defaultMessage(),
                violations,
                exception);
    }
}
