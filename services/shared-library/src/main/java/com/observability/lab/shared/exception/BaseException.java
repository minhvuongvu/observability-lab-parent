package com.observability.lab.shared.exception;

import java.io.Serial;
import java.util.Objects;

/**
 * Root of every exception the platform throws deliberately.
 *
 * <p>Carrying an {@link ErrorCode} means the exception already knows its identifier, its category
 * and therefore its status code and log level. The handler does not need a chain of
 * {@code instanceof} checks, and adding a new failure mode does not mean editing the handler.
 *
 * <p>Unchecked, because none of these are conditions a caller can meaningfully recover from at the
 * point of the call — they are handled once, at the boundary.
 */
public abstract class BaseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;

    protected BaseException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    protected BaseException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    protected BaseException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    /** The identifier a client branches on. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Shorthand for {@code errorCode().code()}. */
    public String code() {
        return errorCode.code();
    }

    /** Shorthand for {@code errorCode().category()}. */
    public ErrorCategory category() {
        return errorCode.category();
    }
}
