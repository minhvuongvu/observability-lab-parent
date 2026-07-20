package com.observability.lab.order.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the application layer needs in order to place an order.
 *
 * <p>Separate from the HTTP request body on purpose. The request DTO is shaped by what is convenient
 * over JSON and is validated by Bean Validation annotations; this is shaped by what the use case
 * needs. Keeping them apart means a change to the wire format does not reach into the use case, and
 * a second entry point — a Kafka consumer, a batch import — can call the same use case without
 * pretending to be an HTTP request.
 *
 * <p>Notably absent: the total. It is computed by the aggregate from the lines, because a total
 * supplied by a caller is a total a caller can get wrong.
 *
 * @param customerId who is placing the order
 * @param currency   ISO 4217 code the whole order is priced in
 * @param items      the lines, at least one
 */
public record CreateOrderCommand(String customerId, String currency, List<Line> items) {

    /**
     * One requested line.
     *
     * @param productSku what to buy
     * @param quantity   how many, positive
     * @param unitPrice  the price agreed now, stored so a later price change cannot rewrite history
     */
    public record Line(String productSku, int quantity, BigDecimal unitPrice) {}
}
