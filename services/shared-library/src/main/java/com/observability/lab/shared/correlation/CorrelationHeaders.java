package com.observability.lab.shared.correlation;

/**
 * HTTP headers that carry correlation across a process boundary.
 *
 * <p>These are read on the way in and echoed on the way out, so a caller who reports a problem can
 * quote an identifier that appears verbatim in the logs.
 */
public final class CorrelationHeaders {

    /** Per-request identifier. Generated when the caller does not supply one. */
    public static final String REQUEST_ID = "X-Request-Id";

    /** Per-business-transaction identifier. Preserved unchanged across every hop. */
    public static final String CORRELATION_ID = "X-Correlation-Id";

    /**
     * Authenticated subject, injected by the gateway after it validates the token.
     *
     * <p>Trusting a header for identity is only safe because nothing reaches a service except
     * through the gateway, which strips any client-supplied value before setting its own. Once the
     * services validate tokens themselves the security context becomes the authoritative source and
     * this header is only a convenience.
     */
    public static final String USER_ID = "X-User-Id";

    /** W3C Trace Context traversal header. Lower-case is mandated by the specification. */
    public static final String TRACEPARENT = "traceparent";

    /** W3C Trace Context vendor-specific state. */
    public static final String TRACESTATE = "tracestate";

    private CorrelationHeaders() {
        throw new AssertionError("No instances.");
    }
}
