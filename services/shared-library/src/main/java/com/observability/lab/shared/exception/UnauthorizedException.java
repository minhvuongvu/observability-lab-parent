package com.observability.lab.shared.exception;

import java.io.Serial;

/**
 * No credentials were supplied, or they did not verify. Renders as 401.
 *
 * <p>Named {@code Unauthorized} rather than {@code AuthenticationException} on purpose: Spring
 * Security already owns that name, and having two types with the same simple name in one codebase
 * guarantees somebody eventually imports the wrong one and catches nothing.
 *
 * <p>The message is never allowed to explain <em>why</em> verification failed. "Token expired"
 * versus "unknown subject" is a probing oracle; the caller gets one answer for every case.
 */
public class UnauthorizedException extends BaseException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UnauthorizedException() {
        super(PlatformErrorCode.UNAUTHENTICATED);
    }

    public UnauthorizedException(String message) {
        super(PlatformErrorCode.UNAUTHENTICATED, message);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(PlatformErrorCode.UNAUTHENTICATED, message, cause);
    }
}
