package com.observability.lab.order.infrastructure.messaging;

import java.time.Instant;
import java.util.List;

/**
 * The Inventory Service's verdict on a reservation, as this service reads it.
 *
 * <p>A separate declaration from the producer's record of the same name, on purpose. Sharing one
 * class between the two services would make the topic's schema a compile-time coupling: neither
 * could change its representation without the other being rebuilt, which is precisely the coupling
 * that publishing events was supposed to remove. The contract is the topic and its documentation;
 * each side owns its own view of it.
 *
 * <p>That is also why unknown fields must not break deserialisation — the producer is free to add
 * one. See {@code KafkaConsumerConfiguration}.
 *
 * @param eventId          unique per publication, so a redelivery is recognisable
 * @param causationEventId the order-created this answers, for joining the two halves in logs
 * @param orderNumber      the order being settled
 * @param outcome          {@code RESERVED} or {@code REJECTED}
 * @param shortages        what could not be satisfied; empty unless rejected
 * @param occurredAt       when the reservation was decided
 */
public record InventoryUpdatedMessage(
        String eventId,
        String causationEventId,
        String orderNumber,
        String outcome,
        List<String> shortages,
        Instant occurredAt) {

    /** The only value that confirms an order. Anything else is treated as a refusal. */
    private static final String RESERVED = "RESERVED";

    /**
     * Whether stock was secured.
     *
     * <p>Fails closed: an outcome this service does not recognise confirms nothing. Guessing the
     * optimistic reading of an unknown verdict would ship goods that were never reserved.
     */
    public boolean isReserved() {
        return RESERVED.equalsIgnoreCase(outcome);
    }
}
