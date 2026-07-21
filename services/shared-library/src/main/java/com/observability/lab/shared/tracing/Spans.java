package com.observability.lab.shared.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;

/**
 * Adds business meaning to spans the agent has already started.
 *
 * <p>The OpenTelemetry agent produces a structurally complete trace on its own: HTTP handlers, JDBC
 * statements, Kafka sends and receives, Redis commands, outbound HTTP. What it cannot know is what
 * any of it <em>means</em> — that a span belongs to order {@code ORD-…}, that a reservation was
 * refused for a shortage rather than a fault, that this consumer span answers that producer span.
 * Supplying that is the only instrumentation worth writing by hand.
 *
 * <p>Everything here operates on {@link Span#current()} and is a no-op when nothing is tracing, so
 * calling it from a service running without the agent costs nothing and breaks nothing.
 */
public final class Spans {

    /** Business attribute keys. Same names as the metric tags and log fields, deliberately. */
    public static final AttributeKey<String> ORDER_NUMBER = AttributeKey.stringKey("order.number");
    public static final AttributeKey<String> ORDER_STATUS = AttributeKey.stringKey("order.status");
    public static final AttributeKey<String> CUSTOMER_ID = AttributeKey.stringKey("order.customer_id");
    public static final AttributeKey<String> CURRENCY = AttributeKey.stringKey("order.currency");
    public static final AttributeKey<Double> ORDER_TOTAL = AttributeKey.doubleKey("order.total_amount");
    public static final AttributeKey<Long> LINE_COUNT = AttributeKey.longKey("order.line_count");
    public static final AttributeKey<String> EVENT_ID = AttributeKey.stringKey("messaging.event_id");
    public static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");

    private Spans() {
        throw new AssertionError("No instances.");
    }

    /** Adds an attribute to the active span, ignoring a null value. */
    public static void attribute(AttributeKey<String> key, String value) {
        if (value != null) {
            Span.current().setAttribute(key, value);
        }
    }

    public static void attribute(AttributeKey<Long> key, long value) {
        Span.current().setAttribute(key, value);
    }

    public static void attribute(AttributeKey<Double> key, double value) {
        Span.current().setAttribute(key, value);
    }

    /**
     * Records a point in time inside the span.
     *
     * <p>An event, not a child span, when the thing being recorded has no duration — "the
     * reservation was refused", "the invoice was rebuilt". A zero-length child span for a moment is
     * noise on a waterfall; an event sits on the timeline where it happened.
     */
    public static void event(String name) {
        Span.current().addEvent(name);
    }

    public static void event(String name, io.opentelemetry.api.common.Attributes attributes) {
        Span.current().addEvent(name, attributes);
    }

    /**
     * Marks the span as failed.
     *
     * <p>Note what is deliberately <em>not</em> here: a way to mark a business refusal as an error.
     * A rejected order is this system working correctly, and setting {@link StatusCode#ERROR} on it
     * would put ordinary operation into the error rate every alert is built on. Refusals are
     * recorded as an {@code outcome} attribute and an event; only genuine faults get an error status.
     */
    public static void failed(String description, Throwable cause) {
        Span span = Span.current();
        span.setStatus(StatusCode.ERROR, description);
        if (cause != null) {
            // recordException captures type, message and stack trace as a span event, which is what
            // makes the failure visible in the trace rather than only in the logs.
            span.recordException(cause);
        }
    }

    /** Marks a span as explicitly successful, for a path where that is not obvious. */
    public static void succeeded() {
        Span.current().setStatus(StatusCode.OK);
    }

    /**
     * Reconstructs a span context from ids carried in a message, so a span can <em>link</em> to it.
     *
     * <p>A link, rather than a parent, is the right relationship when work is caused by something
     * that has already finished — the classic case being a message consumed long after it was
     * produced, or a batch whose items come from many unrelated traces. Parenting a consumer to a
     * producer that completed an hour earlier produces a trace with an hour-long gap in the middle;
     * a link records the causal relationship without distorting the timeline.
     *
     * @return a remote span context, or {@link SpanContext#getInvalid()} when the ids are unusable
     */
    public static SpanContext remoteContext(String traceId, String spanId) {
        if (traceId == null || spanId == null) {
            return SpanContext.getInvalid();
        }
        SpanContext context = SpanContext.createFromRemoteParent(
                traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());

        return context.isValid() ? context : SpanContext.getInvalid();
    }
}
