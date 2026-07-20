package com.observability.lab.shared.exception;

import java.io.Serial;

/**
 * A downstream dependency failed or did not answer in time.
 *
 * <p>Carries the dependency name so the log line and the metric tag both say which one. "Upstream
 * call failed" is nearly useless during an incident; "upstream call to inventory-service failed" is
 * a starting point.
 *
 * <p>Timeouts get their own category, and therefore their own status code — 504 rather than 502 —
 * because the distinction changes what the caller should do. A refused connection is worth retrying
 * immediately; a timeout usually means the dependency is saturated and retrying makes it worse.
 */
public class IntegrationException extends BaseException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String dependency;

    private IntegrationException(ErrorCode errorCode, String dependency, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.dependency = dependency;
    }

    /** The dependency failed: connection refused, protocol error, unexpected status. */
    public static IntegrationException failed(String dependency, String message, Throwable cause) {
        return new IntegrationException(
                PlatformErrorCode.UPSTREAM_FAILED, dependency, decorate(dependency, message), cause);
    }

    /** The dependency did not answer within the configured budget. */
    public static IntegrationException timedOut(String dependency, String message, Throwable cause) {
        return new IntegrationException(
                PlatformErrorCode.UPSTREAM_TIMEOUT, dependency, decorate(dependency, message), cause);
    }

    /** Which dependency was involved, for log fields and metric tags. */
    public String dependency() {
        return dependency;
    }

    /** Whether this was a timeout rather than an outright failure. */
    public boolean isTimeout() {
        return category() == ErrorCategory.TIMEOUT;
    }

    private static String decorate(String dependency, String message) {
        return "Call to '" + dependency + "' failed: " + message;
    }
}
