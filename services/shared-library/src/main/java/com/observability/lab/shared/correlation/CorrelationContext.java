package com.observability.lab.shared.correlation;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Reads and writes the correlation fields held in the SLF4J {@link MDC}.
 *
 * <p>The MDC is thread-local, which is what makes it useful and what makes it dangerous. Every entry
 * point that populates it must clear it again on the way out, including the error path, because the
 * thread goes back into a pool and the next request would otherwise inherit the previous request's
 * identity. {@link #clear()} exists for exactly that, and {@link CorrelationFilter} calls it in a
 * {@code finally} block.
 */
public final class CorrelationContext {

    private CorrelationContext() {
        throw new AssertionError("No instances.");
    }

    // --- Reading -----------------------------------------------------------

    public static String traceId() {
        return MDC.get(CorrelationFields.TRACE_ID);
    }

    public static String spanId() {
        return MDC.get(CorrelationFields.SPAN_ID);
    }

    public static String requestId() {
        return MDC.get(CorrelationFields.REQUEST_ID);
    }

    public static String correlationId() {
        return MDC.get(CorrelationFields.CORRELATION_ID);
    }

    public static Optional<String> userId() {
        return Optional.ofNullable(MDC.get(CorrelationFields.USER_ID));
    }

    // --- Writing -----------------------------------------------------------

    /**
     * Sets a field, removing it instead when the value is {@code null} or blank.
     *
     * <p>An empty MDC entry is worse than a missing one: it serialises as {@code "user_id": ""},
     * which looks like a value and quietly breaks queries that filter on presence.
     */
    public static void put(String field, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(field);
        } else {
            MDC.put(field, value);
        }
    }

    public static void traceId(String value) {
        put(CorrelationFields.TRACE_ID, value);
    }

    public static void spanId(String value) {
        put(CorrelationFields.SPAN_ID, value);
    }

    public static void requestId(String value) {
        put(CorrelationFields.REQUEST_ID, value);
    }

    public static void correlationId(String value) {
        put(CorrelationFields.CORRELATION_ID, value);
    }

    /**
     * Records the authenticated subject.
     *
     * <p>Called once the security context is established, which happens after the correlation filter
     * has already run — authentication needs the request to be logged even when it fails.
     */
    public static void userId(String value) {
        put(CorrelationFields.USER_ID, value);
    }

    // --- Lifecycle ---------------------------------------------------------

    /** Removes only the fields this library owns, leaving any other MDC content untouched. */
    public static void clear() {
        CorrelationFields.ALL.forEach(MDC::remove);
    }

    /**
     * Copies the fields this library owns, for handing to another thread.
     *
     * @return a detached copy, never {@code null}
     * @see MdcTaskDecorator
     */
    public static Map<String, String> snapshot() {
        Map<String, String> copy = new HashMap<>();
        for (String field : CorrelationFields.ALL) {
            String value = MDC.get(field);
            if (value != null) {
                copy.put(field, value);
            }
        }
        return copy;
    }

    /** Applies a {@link #snapshot()} onto the current thread, replacing what is there. */
    public static void restore(Map<String, String> snapshot) {
        clear();
        if (snapshot != null) {
            snapshot.forEach(CorrelationContext::put);
        }
    }

    /**
     * Generates an identifier for a request or correlation that arrived without one.
     *
     * <p>Dashes are stripped so the value is the same shape as a trace id, which keeps log output
     * aligned and makes the two visually comparable.
     */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
