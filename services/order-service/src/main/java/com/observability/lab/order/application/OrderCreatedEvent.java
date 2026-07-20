package com.observability.lab.order.application;

import com.observability.lab.order.domain.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published when an order has been accepted and needs stock reserved.
 *
 * <p>This is a fact about the past, not a request: it says an order <em>was</em> created, and any
 * number of services may react or none at all. That is what makes the Order Service able to accept
 * an order while the Inventory Service is down.
 *
 * <p>Raised first as a Spring application event and only sent to Kafka after the database
 * transaction commits — see {@code KafkaOrderEventPublisher}. Publishing inside the transaction
 * would announce orders that then roll back.
 *
 * @param eventId     unique per publication, so a consumer can recognise a redelivery. At-least-once
 *                    delivery makes duplicates normal, and this is what makes them harmless.
 * @param orderNumber the order this concerns
 * @param customerId  who placed it
 * @param totalAmount order total
 * @param currency    ISO 4217 code
 * @param items       what to reserve
 * @param occurredAt  when the order was accepted, not when the message was sent
 */
public record OrderCreatedEvent(
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
     * <p>Carries no price: what the customer paid is the Order Service's business, and a consumer
     * that does not need a field should not be handed it.
     */
    public record Item(String productSku, int quantity) {}

    static OrderCreatedEvent from(Order order) {
        return new OrderCreatedEvent(
                UUID.randomUUID().toString(),
                order.getOrderNumber(),
                order.getCustomerId(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getItems().stream()
                        .map(item -> new Item(item.getProductSku(), item.getQuantity()))
                        .toList(),
                Instant.now());
    }
}
