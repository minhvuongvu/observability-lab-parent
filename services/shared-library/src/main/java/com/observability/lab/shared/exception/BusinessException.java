package com.observability.lab.shared.exception;

import java.io.Serial;

/**
 * A business rule was enforced and the request cannot proceed.
 *
 * <p>Insufficient stock, an illegal status transition, an order already cancelled. These are not
 * errors — they are the domain doing its job, which is why the category logs at {@code INFO}.
 *
 * <p>Always constructed with a context-specific {@link ErrorCode}: the shared library has no
 * business rules of its own, so it cannot supply a sensible default.
 */
public class BusinessException extends BaseException {

    @Serial
    private static final long serialVersionUID = 1L;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
