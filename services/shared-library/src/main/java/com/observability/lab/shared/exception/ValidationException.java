package com.observability.lab.shared.exception;

import com.observability.lab.shared.api.FieldViolation;
import java.io.Serial;
import java.util.List;

/**
 * The request is malformed and the caller has to change something.
 *
 * <p>Thrown by programmatic validation in the application layer. Bean Validation failures raised by
 * the framework are translated into the same response shape by the global handler, so a caller
 * cannot tell which mechanism rejected the request — and should not have to.
 */
public class ValidationException extends BaseException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Transient because {@link FieldViolation} is a plain record and is not serialisable, while
     * {@link Throwable} is. Without this, serialising the exception would fail at runtime.
     */
    private final transient List<FieldViolation> violations;

    public ValidationException(String message) {
        this(PlatformErrorCode.VALIDATION_FAILED, message, List.of());
    }

    public ValidationException(String message, List<FieldViolation> violations) {
        this(PlatformErrorCode.VALIDATION_FAILED, message, violations);
    }

    public ValidationException(ErrorCode errorCode, String message, List<FieldViolation> violations) {
        super(errorCode, message);
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /** @return the field-level failures, never {@code null} */
    public List<FieldViolation> violations() {
        // Never null in practice; the guard covers a deserialised instance, where the transient
        // field is restored as null.
        return violations == null ? List.of() : violations;
    }
}
