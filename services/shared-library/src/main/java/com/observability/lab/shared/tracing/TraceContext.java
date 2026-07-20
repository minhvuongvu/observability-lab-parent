package com.observability.lab.shared.tracing;

import com.observability.lab.shared.correlation.CorrelationFields;
import java.util.Optional;
import org.slf4j.MDC;

/**
 * Reads the identifiers of the trace the current thread is working on.
 *
 * <p>The values are read from the MDC rather than from a tracing SDK, which keeps this usable
 * before any SDK is on the classpath and keeps the API stable once one is.
 *
 * <p>Two key spellings are checked. This platform's log schema uses {@code trace_id} and
 * {@code span_id}; Micrometer Tracing, which arrives with the tracing step, publishes
 * {@code traceId} and {@code spanId}. Reading both means instrumentation can be introduced without
 * a flag day where every caller of this class has to change at once.
 */
public final class TraceContext {

    /** Key Micrometer Tracing writes to the MDC. */
    private static final String MICROMETER_TRACE_ID = "traceId";

    /** Key Micrometer Tracing writes to the MDC. */
    private static final String MICROMETER_SPAN_ID = "spanId";

    private TraceContext() {
        throw new AssertionError("No instances.");
    }

    /** @return the active trace id, or {@code null} when the thread is not inside a trace */
    public static String traceId() {
        return firstPresent(CorrelationFields.TRACE_ID, MICROMETER_TRACE_ID);
    }

    /** @return the active span id, or {@code null} when the thread is not inside a span */
    public static String spanId() {
        return firstPresent(CorrelationFields.SPAN_ID, MICROMETER_SPAN_ID);
    }

    /** @return whether the current thread is working inside a known trace */
    public static boolean isTracing() {
        return traceId() != null;
    }

    /**
     * Parses an inbound {@code traceparent} header.
     *
     * @param header raw header value, may be {@code null}
     * @return the parsed header, or empty when absent or malformed
     */
    public static Optional<TraceParent> parseTraceParent(String header) {
        return TraceParent.parse(header);
    }

    private static String firstPresent(String preferred, String fallback) {
        String value = MDC.get(preferred);
        return value != null ? value : MDC.get(fallback);
    }
}
