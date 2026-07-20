package com.observability.lab.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An event this service has already applied.
 *
 * <p>Kafka guarantees at-least-once delivery, so a record redelivered after a database commit whose
 * offset commit did not survive is ordinary operation, not a fault. Without de-duplication that
 * redelivery reserves the same stock twice, and the discrepancy is discovered days later by someone
 * counting boxes.
 *
 * <p>Written in the same transaction as the stock change it accompanies. That is the entire trick:
 * either both are durable or neither is, so there is no window in which the event is marked handled
 * but its effect was rolled back.
 *
 * <p>Deliberately not an {@link com.observability.lab.shared.persistence.AuditableEntity}. The event
 * id is a natural key supplied by the producer, there is nothing to version because the row is never
 * updated, and "who created it" is always the consumer.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    /** Which kind of event, so entries can be pruned or audited per stream. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /** For JPA only. */
    protected ProcessedEvent() {}

    public ProcessedEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
