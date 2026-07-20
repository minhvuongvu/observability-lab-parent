package com.observability.lab.order.application;

import com.observability.lab.order.domain.Order;
import com.observability.lab.order.domain.OrderItem;
import com.observability.lab.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The read model for an order: what a caller is shown, and what the cache stores.
 *
 * <p>Deliberately not the JPA entity. Returning {@link Order} from a use case would expose lazy
 * associations outside the transaction, tie the JSON contract to the table layout, and put a mutable
 * managed entity into a Redis cache — three separate ways to be surprised later.
 *
 * <p>This single record serves both the cache and the HTTP response. A second, identical DTO in the
 * API layer would be pure ceremony; the trade accepted here is that a change to the public JSON
 * contract is a change to this type, and has to be recognised as such.
 *
 * @param orderNumber  the business key callers address the order by
 * @param customerId   who placed it
 * @param status       where it is in its lifecycle
 * @param totalAmount  sum of the lines, computed by the aggregate
 * @param currency     ISO 4217 code
 * @param items        the lines
 * @param createdAt    when the order was accepted
 * @param updatedAt    when it last changed, {@code null} if never
 */
public record OrderView(
        String orderNumber,
        String customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        List<OrderItemView> items,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * One line of the read model.
     *
     * @param lineTotal computed rather than left to the client, so every consumer agrees on it
     */
    public record OrderItemView(
            String productSku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {

        static OrderItemView from(OrderItem item) {
            return new OrderItemView(
                    item.getProductSku(), item.getQuantity(), item.getUnitPrice(), item.lineTotal());
        }
    }

    /**
     * Projects an aggregate into its read model.
     *
     * <p>Must be called inside the transaction that loaded the order: it walks the lazy item
     * collection, which is unavailable once the session closes.
     */
    public static OrderView from(Order order) {
        return new OrderView(
                order.getOrderNumber(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getItems().stream().map(OrderItemView::from).toList(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
