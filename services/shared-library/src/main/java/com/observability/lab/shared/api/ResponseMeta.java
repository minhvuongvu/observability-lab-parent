package com.observability.lab.shared.api;

import com.observability.lab.shared.correlation.CorrelationContext;
import com.observability.lab.shared.tracing.TraceContext;
import java.time.Instant;

/**
 * Identifying metadata attached to every response, successful or not.
 *
 * <p>The trace id is the point of this record. When a caller reports "it failed at about three
 * o'clock", the alternative to handing them an identifier is searching logs by timestamp and hoping.
 * With it, the exact request is one query away.
 *
 * @param timestamp     when the response was produced
 * @param requestId     identifier of this single request
 * @param correlationId identifier of the business transaction this request belongs to
 * @param traceId       identifier of the distributed trace, when the request is being traced
 */
public record ResponseMeta(Instant timestamp, String requestId, String correlationId, String traceId) {

    /** Builds metadata from the correlation fields on the current thread. */
    public static ResponseMeta current() {
        return new ResponseMeta(
                Instant.now(),
                CorrelationContext.requestId(),
                CorrelationContext.correlationId(),
                TraceContext.traceId());
    }
}
