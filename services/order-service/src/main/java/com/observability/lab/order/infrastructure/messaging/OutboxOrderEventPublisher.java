package com.observability.lab.order.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.observability.lab.order.application.OrderCreatedEvent;
import com.observability.lab.order.domain.OutboxEvent;
import com.observability.lab.order.infrastructure.persistence.OutboxEventJpaRepository;
import com.observability.lab.shared.exception.TechnicalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Writes an accepted order's event into the outbox, inside the order's own transaction.
 *
 * <p>The phase is the entire point. {@link TransactionPhase#BEFORE_COMMIT} runs while the
 * transaction is still open, so the outbox row and the order row reach disk in the same commit:
 * there is no instant at which one exists without the other. The previous implementation sent to
 * Kafka on {@code AFTER_COMMIT}, which was correct about never announcing an order that rolled back
 * but could still lose the event outright if the broker was unreachable — the order was committed and
 * nothing would ever mention it again.
 *
 * <p>Nothing here talks to Kafka. Delivery is {@link OutboxRelay}'s problem, and separating the two
 * is what lets an order be accepted while the broker is down.
 */
@Component
public class OutboxOrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxOrderEventPublisher.class);
    private static final String EVENT_TYPE = "order-created";

    private final OutboxEventJpaRepository outbox;
    private final ObjectMapper objectMapper;
    private final String topic;

    public OutboxOrderEventPublisher(OutboxEventJpaRepository outbox, ObjectMapper objectMapper,
            @Value("${app.kafka.topics.order-created}") String topic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        outbox.save(OutboxEvent.pending(
                event.eventId(),
                EVENT_TYPE,
                topic,
                // Keyed by order number so every event for one order lands on the same partition and
                // is therefore delivered in order. Keying by anything else would let a cancellation
                // overtake the creation it refers to.
                event.orderNumber(),
                serialise(event),
                event.occurredAt()));

        log.debug("Enqueued {} for order '{}' in the outbox", EVENT_TYPE, event.orderNumber());
    }

    /**
     * Renders the event with the application's own mapper, so the bytes on the wire match the
     * timestamps the HTTP API produces.
     *
     * <p>A failure here is deliberately allowed to abort the transaction. An event that cannot be
     * serialised will never be deliverable, and accepting the order anyway would leave stock
     * unreserved with nothing to reconcile against.
     */
    private String serialise(OrderCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new TechnicalException(
                    "Could not serialise " + EVENT_TYPE + " for order '" + event.orderNumber() + "'.",
                    exception);
        }
    }
}
