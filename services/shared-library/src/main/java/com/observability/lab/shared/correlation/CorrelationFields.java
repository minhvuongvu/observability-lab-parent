package com.observability.lab.shared.correlation;

import java.util.List;

/**
 * MDC keys that identify a request across every log line the platform emits.
 *
 * <p>The names are snake_case because they end up as JSON field names in the log pipeline, where
 * they are queried directly. Changing one of these constants changes the log schema and breaks every
 * saved query and dashboard built on it, so they are treated as a public contract.
 *
 * @see <a href="../../../../../../../../../docs/SystemDesign.md">docs/SystemDesign.md, section 4.2</a>
 */
public final class CorrelationFields {

    /** Identifies the whole distributed operation. Shared by every service it touches. */
    public static final String TRACE_ID = "trace_id";

    /** Identifies one unit of work inside a trace. */
    public static final String SPAN_ID = "span_id";

    /** Identifies a single inbound HTTP request. Generated at the edge if absent. */
    public static final String REQUEST_ID = "request_id";

    /** Identifies one business transaction, which may span several requests. Caller-supplied. */
    public static final String CORRELATION_ID = "correlation_id";

    /** Subject of the authenticated principal, when there is one. */
    public static final String USER_ID = "user_id";

    /** Logical service name, from {@code app.name}. */
    public static final String SERVICE = "service";

    /** Deployment environment, from {@code app.environment}. */
    public static final String ENVIRONMENT = "environment";

    /** Build version, from {@code app.version}. */
    public static final String VERSION = "version";

    /**
     * Every field this library manages, in the order a human reads them.
     *
     * <p>Used to clear precisely what was set. Wiping the entire MDC instead would discard keys put
     * there by other libraries, and on a pooled request thread that turns into a bug that only shows
     * up under load.
     */
    public static final List<String> ALL = List.of(
            TRACE_ID, SPAN_ID, REQUEST_ID, CORRELATION_ID, USER_ID, SERVICE, ENVIRONMENT, VERSION);

    private CorrelationFields() {
        throw new AssertionError("No instances.");
    }
}
