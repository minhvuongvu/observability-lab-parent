package com.observability.lab.inventory.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The {@code order-created} event as this service reads it.
 *
 * <p>A copy of the Order Service's contract, owned here. Sharing a DTO module between the two would
 * couple their release cycles: neither could change its own event without recompiling the other, and
 * the whole point of the broker is that they are independently deployable.
 *
 * <p>The producer sends no type headers, so the target type is fixed by the deserialiser rather than
 * read from the payload. Unknown fields are ignored, which is what lets the producer add one without
 * coordinating a release here.
 *
 * @param eventId     unique per publication; the key this service de-duplicates on
 * @param orderNumber the order that needs stock
 * @param customerId  who placed it, carried for log context rather than for any rule
 * @param totalAmount order total, unused here but part of the published event
 * @param currency    ISO 4217 code
 * @param items       what to reserve
 * @param occurredAt  when the order was accepted, not when the message was sent
 */
public record OrderCreatedMessage(
        String eventId,
        String orderNumber,
        String customerId,
        BigDecimal totalAmount,
        String currency,
        List<Item> items,
        Instant occurredAt) {

    /**
     * One line to reserve.
     *
     * @param productSku the product
     * @param quantity   how many units
     */
    public record Item(String productSku, int quantity) {}
}
