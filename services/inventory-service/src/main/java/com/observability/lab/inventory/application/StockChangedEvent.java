package com.observability.lab.inventory.application;

import java.time.Instant;

/**
 * A stock level changed. Raised in-process, after the change is committed.
 *
 * <p>Exists so the {@code WatchStockLevels} server stream has something to stream. Without a change
 * feed the only honest implementation of a live subscription is polling the database on a timer,
 * which is worse in every dimension: later, heavier, and it reports changes that were already
 * overwritten.
 *
 * <p>Deliberately in-process and deliberately not durable. This is a <em>notification</em> that a
 * number moved, for subscribers who are connected right now; the authoritative record of what
 * happened is the {@code stock_movements} table, and the cross-service announcement is the
 * {@code inventory-updated} Kafka event. A subscriber that reconnects re-reads current state rather
 * than replaying what it missed — which is why {@code send_initial_state} is in the contract.
 *
 * @param productSku        the product whose level moved
 * @param availableQuantity units that can still be promised, after the change
 * @param reservedQuantity  units promised to accepted orders, after the change
 * @param occurredAt        when the change was applied
 * @param reason            what moved it
 */
public record StockChangedEvent(
        String productSku,
        int availableQuantity,
        int reservedQuantity,
        Instant occurredAt,
        Reason reason) {

    public enum Reason {
        RESERVED,
        RELEASED,
        RECEIVED,
        ADJUSTED
    }

    static StockChangedEvent of(com.observability.lab.inventory.domain.StockLevel stock, Reason reason) {
        return new StockChangedEvent(stock.getProductSku(), stock.getAvailableQuantity(),
                stock.getReservedQuantity(), Instant.now(), reason);
    }
}
