package com.observability.lab.shared.logging;

import com.observability.lab.shared.correlation.CorrelationContext;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * A scoped set of extra log fields.
 *
 * <p>Structured logging is only useful if the fields are queryable, which means putting them in the
 * MDC rather than interpolating them into the message. Doing that by hand is error-prone: the
 * remove is easy to forget, and on the error path it usually is forgotten, so the value bleeds into
 * unrelated log lines on the same pooled thread.
 *
 * <pre>{@code
 * try (var scope = LogContext.with("order_id", orderId).and("customer_id", customerId)) {
 *     log.info("Order accepted");     // both fields present
 * }
 * log.info("Batch finished");         // neither field present
 * }</pre>
 *
 * <p>Any value the keys held beforehand is restored on close, so nesting scopes is safe.
 */
public final class LogContext implements AutoCloseable {

    /** Key to previous value. A {@code null} value records "was not set". */
    private final Map<String, String> previousValues = new HashMap<>();

    private LogContext() {}

    /** Opens a scope carrying one field. */
    public static LogContext with(String key, String value) {
        return new LogContext().and(key, value);
    }

    /** Opens a scope carrying several fields. */
    public static LogContext with(Map<String, String> fields) {
        LogContext context = new LogContext();
        fields.forEach(context::and);
        return context;
    }

    /**
     * Adds another field to this scope.
     *
     * <p>The original value is remembered only the first time a key is touched, so setting the same
     * key twice within one scope still restores what was there before the scope opened.
     */
    public LogContext and(String key, String value) {
        previousValues.putIfAbsent(key, MDC.get(key));
        CorrelationContext.put(key, value);
        return this;
    }

    @Override
    public void close() {
        previousValues.forEach((key, previous) -> {
            if (previous == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, previous);
            }
        });
        previousValues.clear();
    }
}
