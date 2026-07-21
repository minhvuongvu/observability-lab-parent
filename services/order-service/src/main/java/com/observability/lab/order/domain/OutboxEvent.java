package com.observability.lab.order.domain;

import com.observability.lab.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An event owed to Kafka, stored in the same transaction as the change that produced it.
 *
 * <p>The row exists because PostgreSQL and Kafka cannot commit together. Sending after the
 * transaction commits leaves a window where the order is durable and the event is lost; sending
 * before it leaves the opposite window, where consumers act on an order that rolled back. Writing the
 * event as a row removes the window entirely — it is part of the same commit as the order — and
 * leaves only the tractable problem of moving durable rows onto a broker.
 *
 * <p>Deliberately not an {@link com.observability.lab.shared.persistence.AuditableEntity}: "who
 * created this" is always the service itself, and the timestamps that matter here are domain facts
 * ({@code occurredAt}) and delivery facts ({@code publishedAt}), not audit metadata.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent extends BaseEntity<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "outbox_event_id_generator")
    // allocationSize must equal the sequence's INCREMENT BY in V2__create_outbox.sql.
    @SequenceGenerator(name = "outbox_event_id_generator", sequenceName = "outbox_event_id_seq",
            allocationSize = 50)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 120)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 64)
    private String messageKey;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /**
     * The correlation context this event was produced in.
     *
     * <p>Part of the event's durable state rather than ambient thread state, because the relay that
     * publishes it runs minutes later on a scheduler thread with no MDC to recover. Without this the
     * chain breaks at the broker and one business transaction appears under two identifiers.
     */
    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** For JPA only. */
    protected OutboxEvent() {}

    /**
     * Enqueues an event for delivery.
     *
     * <p>The payload arrives already serialised. Serialising here would make the relay's behaviour
     * depend on an {@code ObjectMapper} configured somewhere else, and a payload that cannot be
     * written is a bug worth discovering in the transaction that produced it rather than minutes
     * later on a scheduler thread.
     */
    public static OutboxEvent pending(String eventId, String eventType, String topic,
            String messageKey, String payload, Instant occurredAt,
            String correlationId, String traceId) {

        OutboxEvent event = new OutboxEvent();
        event.eventId = eventId;
        event.eventType = eventType;
        event.topic = topic;
        event.messageKey = messageKey;
        event.payload = payload;
        event.occurredAt = occurredAt;
        event.correlationId = correlationId;
        event.traceId = traceId;
        event.attempts = 0;
        return event;
    }

    /** The broker acknowledged the send. The row becomes history. */
    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /**
     * The send failed. The row stays unpublished and will be picked up again.
     *
     * <p>The reason is truncated rather than stored whole: a driver stack trace can run to
     * kilobytes, and the outbox is a queue, not a log destination.
     */
    public void markFailed(String reason) {
        this.attempts++;
        this.lastError = reason == null ? null
                : reason.substring(0, Math.min(reason.length(), MAX_ERROR_LENGTH));
    }

    private static final int MAX_ERROR_LENGTH = 1_000;

    public boolean isPublished() {
        return publishedAt != null;
    }

    @Override
    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getTraceId() {
        return traceId;
    }
}
