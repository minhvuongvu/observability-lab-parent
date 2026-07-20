package com.observability.lab.shared.tracing;

import java.util.Locale;
import java.util.Optional;

/**
 * A parsed W3C Trace Context {@code traceparent} header.
 *
 * <p>The wire format is fixed at 55 characters:
 *
 * <pre>
 *   00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
 *   ^  ^                                ^                ^
 *   |  |                                |                trace flags (2 hex)
 *   |  |                                parent span id  (16 hex)
 *   |  trace id                                         (32 hex)
 *   version                                             (2 hex)
 * </pre>
 *
 * <p>This type exists so the platform can read an incoming trace id before any tracing SDK is
 * wired up, and so a malformed header from an untrusted caller is rejected rather than propagated.
 * Once the OpenTelemetry SDK is in place it owns propagation; this remains the fallback for reading
 * the identifier into the log context.
 *
 * @param version    protocol version, two lower-case hex digits
 * @param traceId    32 lower-case hex digits, never all zero
 * @param parentId   16 lower-case hex digits, never all zero
 * @param traceFlags two lower-case hex digits; bit 0 is the sampled flag
 * @see <a href="https://www.w3.org/TR/trace-context/">W3C Trace Context</a>
 */
public record TraceParent(String version, String traceId, String parentId, String traceFlags) {

    private static final int EXPECTED_LENGTH = 55;
    private static final String INVALID_VERSION = "ff";
    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_PARENT_ID = "0".repeat(16);

    /**
     * Parses a {@code traceparent} header value.
     *
     * <p>Returns empty rather than throwing: a malformed header is a routine event on a public
     * endpoint, not an exceptional one, and the correct response is to start a fresh trace rather
     * than to fail the request.
     *
     * <p>The specification mandates lower-case hex. Input is accepted case-insensitively and
     * normalised, because rejecting an otherwise well-formed header over letter case loses a trace
     * for no benefit.
     *
     * @param header raw header value, may be {@code null}
     * @return the parsed header, or empty when it is absent or malformed
     */
    public static Optional<TraceParent> parse(String header) {
        if (header == null || header.length() != EXPECTED_LENGTH) {
            return Optional.empty();
        }
        String normalised = header.toLowerCase(Locale.ROOT);
        String[] parts = normalised.split("-");
        if (parts.length != 4) {
            return Optional.empty();
        }

        String version = parts[0];
        String traceId = parts[1];
        String parentId = parts[2];
        String flags = parts[3];

        boolean wellFormed = version.length() == 2 && isHex(version) && !INVALID_VERSION.equals(version)
                && traceId.length() == 32 && isHex(traceId) && !ZERO_TRACE_ID.equals(traceId)
                && parentId.length() == 16 && isHex(parentId) && !ZERO_PARENT_ID.equals(parentId)
                && flags.length() == 2 && isHex(flags);

        return wellFormed
                ? Optional.of(new TraceParent(version, traceId, parentId, flags))
                : Optional.empty();
    }

    /** Whether the caller has already decided this trace is recorded. */
    public boolean sampled() {
        return (Integer.parseInt(traceFlags, 16) & 0x01) != 0;
    }

    /** Renders this back to its wire format. */
    public String value() {
        return version + "-" + traceId + "-" + parentId + "-" + traceFlags;
    }

    private static boolean isHex(String candidate) {
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }
}
