package com.observability.lab.shared.correlation;

import com.observability.lab.shared.tracing.TraceParent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the correlation context for every inbound request, and tears it down afterwards.
 *
 * <p>Runs as early as the filter chain allows, so that anything logged during authentication,
 * authorisation or request parsing already carries a trace id. A failure logged before the context
 * exists is a failure nobody can correlate — and authentication failures are precisely the ones
 * worth correlating.
 *
 * <p>The teardown in the {@code finally} block is the part that must never be removed. The request
 * thread goes back into a pool; without the clear, the next request inherits the previous
 * request's identity and the logs confidently attribute one user's activity to another.
 */
public class CorrelationFilter extends OncePerRequestFilter {

    /**
     * Longest caller-supplied identifier accepted.
     *
     * <p>Every identifier is copied onto every log line for the request. An unbounded value from an
     * untrusted caller is therefore an amplification primitive: one request, megabytes of log
     * volume, multiplied across the pipeline.
     */
    private static final int MAX_ID_LENGTH = 128;

    private final ServiceIdentity identity;

    public CorrelationFilter(ServiceIdentity identity) {
        this.identity = identity;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            populateContext(request);

            // Set before the chain runs: once the response is committed headers cannot be added,
            // and a streaming endpoint commits early.
            response.setHeader(CorrelationHeaders.REQUEST_ID, CorrelationContext.requestId());
            response.setHeader(CorrelationHeaders.CORRELATION_ID, CorrelationContext.correlationId());

            chain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }

    private void populateContext(HttpServletRequest request) {
        String requestId = sanitise(request.getHeader(CorrelationHeaders.REQUEST_ID));
        if (requestId == null) {
            requestId = CorrelationContext.newId();
        }

        // Trace identity, from the most authoritative source available.
        //
        // When the OpenTelemetry agent is attached it has already started a real server span by the
        // time this filter runs, and *that* is the truth: it reflects the span this request actually
        // is, not the caller's parent. Reading the header instead would put the caller's span id on
        // every log line and quietly break the link between a log and its trace.
        //
        // Without the agent there is no span context, and the inbound traceparent is the best
        // available answer - which keeps the log schema populated in a plain `java -jar` run.
        String traceId = null;
        String spanId = null;

        SpanContext current = Span.current().getSpanContext();
        if (current.isValid()) {
            traceId = current.getTraceId();
            spanId = current.getSpanId();
        } else {
            TraceParent traceParent =
                    TraceParent.parse(request.getHeader(CorrelationHeaders.TRACEPARENT)).orElse(null);
            if (traceParent != null) {
                traceId = traceParent.traceId();
                spanId = traceParent.parentId();
            }
        }

        // Correlation survives across requests, so a caller-supplied value always wins. Falling back
        // to the trace id keeps a business transaction and its trace addressable by one identifier.
        String correlationId = sanitise(request.getHeader(CorrelationHeaders.CORRELATION_ID));
        if (correlationId == null) {
            correlationId = traceId != null ? traceId : requestId;
        }

        CorrelationContext.requestId(requestId);
        CorrelationContext.correlationId(correlationId);
        CorrelationContext.traceId(traceId);
        CorrelationContext.spanId(spanId);
        CorrelationContext.userId(sanitise(request.getHeader(CorrelationHeaders.USER_ID)));

        CorrelationContext.put(CorrelationFields.SERVICE, identity.name());
        CorrelationContext.put(CorrelationFields.ENVIRONMENT, identity.environment());
        CorrelationContext.put(CorrelationFields.VERSION, identity.version());
    }

    /**
     * Accepts a caller-supplied identifier only if it is safe to write into a log line.
     *
     * <p>Rejects anything oversized or containing a character outside {@code [A-Za-z0-9._-]}. The
     * character restriction is not cosmetic: a newline in a header value lets a caller forge log
     * entries, and in a JSON pipeline a quote or brace can break the record the parser is building.
     *
     * @return the accepted value, or {@code null} to mean "generate a fresh one instead"
     */
    private static String sanitise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_ID_LENGTH) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (!safe) {
                return null;
            }
        }
        return trimmed;
    }
}
