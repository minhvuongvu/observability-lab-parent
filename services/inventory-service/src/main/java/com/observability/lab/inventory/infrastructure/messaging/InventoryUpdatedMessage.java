package com.observability.lab.inventory.infrastructure.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The settlement of a reservation, published so the Order Service can finish the order.
 *
 * <p>The reply half of the order flow: {@code order-created} asked for stock, this answers. It is
 * still a fact rather than a command — "stock was reserved for this order" — and the Order Service
 * decides what that means for the order's status.
 *
 * <p>Carries {@code causationEventId}, the id of the {@code order-created} that produced it. That is
 * what lets a trace, or a person reading two services' logs, join the request to its answer without
 * guessing from timestamps.
 *
 * @param eventId          unique per publication, so the Order Service can recognise a redelivery
 * @param causationEventId the order-created event this answers
 * @param orderNumber      the order being settled, and the partition key
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

    public static InventoryUpdatedMessage of(String causationEventId, String orderNumber,
            String outcome, List<String> shortages) {

        return new InventoryUpdatedMessage(
                UUID.randomUUID().toString(),
                causationEventId,
                orderNumber,
                outcome,
                shortages == null ? List.of() : List.copyOf(shortages),
                Instant.now());
    }
}
