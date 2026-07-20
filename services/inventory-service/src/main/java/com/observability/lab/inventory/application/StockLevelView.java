package com.observability.lab.inventory.application;

import com.observability.lab.inventory.domain.StockLevel;
import java.time.Instant;

/**
 * The read model for a stock level: what a caller is shown, and what the cache stores.
 *
 * <p>Deliberately not the JPA entity. Returning {@link StockLevel} would expose its lazy movement
 * collection outside the transaction, tie the JSON contract to the table layout, and place a mutable
 * managed entity into a distributed cache.
 *
 * <p>The first three fields are the contract the Order Service's Feign client reads. Fields may be
 * added — its deserialiser ignores what it does not know — but these three may not be renamed
 * without breaking it.
 *
 * @param productSku        the product
 * @param availableQuantity units that can still be promised
 * @param reservedQuantity  units already promised to accepted orders
 * @param totalQuantity     units physically on hand: available plus reserved
 * @param createdAt         when the product started being tracked
 * @param updatedAt         when the level last changed, {@code null} if never
 */
public record StockLevelView(
        String productSku,
        int availableQuantity,
        int reservedQuantity,
        int totalQuantity,
        Instant createdAt,
        Instant updatedAt) {

    /** Must be called inside the transaction that loaded the aggregate. */
    public static StockLevelView from(StockLevel stockLevel) {
        return new StockLevelView(
                stockLevel.getProductSku(),
                stockLevel.getAvailableQuantity(),
                stockLevel.getReservedQuantity(),
                stockLevel.totalQuantity(),
                stockLevel.getCreatedAt(),
                stockLevel.getUpdatedAt());
    }
}
