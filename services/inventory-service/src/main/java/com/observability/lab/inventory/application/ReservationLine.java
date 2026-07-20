package com.observability.lab.inventory.application;

/**
 * One product and quantity an order needs reserved.
 *
 * <p>Carries no price. What the customer paid is the Order Service's business, and a consumer that
 * does not need a field should not be handed it.
 *
 * @param productSku the product
 * @param quantity   how many units, positive
 */
public record ReservationLine(String productSku, int quantity) {}
