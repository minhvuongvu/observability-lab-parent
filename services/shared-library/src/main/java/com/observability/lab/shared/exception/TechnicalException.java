package com.observability.lab.shared.exception;

import java.io.Serial;

/**
 * Something failed that was not supposed to be able to fail. Renders as 500.
 *
 * <p>Use this when a defensive branch is reached, or to wrap a low-level failure that has no
 * meaningful business interpretation. It is always logged with a stack trace, because by definition
 * nobody anticipated it and the trace is the only evidence.
 *
 * <p>The message reaches the caller only outside production, where {@code server.error.include-message}
 * is enabled. In production the caller gets the code and the trace id, and the detail stays in the
 * logs where it belongs.
 */
public class TechnicalException extends BaseException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TechnicalException(String message) {
        super(PlatformErrorCode.INTERNAL_ERROR, message);
    }

    public TechnicalException(String message, Throwable cause) {
        super(PlatformErrorCode.INTERNAL_ERROR, message, cause);
    }
}
