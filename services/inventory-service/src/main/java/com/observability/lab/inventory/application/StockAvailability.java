package com.observability.lab.inventory.application;

import java.time.Instant;

/**
 * An advisory answer to "is there stock for this SKU right now".
 *
 * <p>Distinct from {@link StockLevelView}, which is the full read model. This carries only what a
 * caller needs in order to decide whether to proceed, plus the two fields that stop it being
 * misread:
 *
 * <ul>
 *   <li>{@code tracked} separates <em>"tracked, and none left"</em> from <em>"inventory has never
 *       heard of this SKU"</em>. Both would otherwise arrive as {@code availableQuantity == 0}, and
 *       they call for completely different responses — one is a stock-out, the other is a catalogue
 *       bug.
 *   <li>{@code asOf} lets a caller reason about staleness rather than assume the number is current.
 *       It is not: another caller may reserve the same units microseconds later.
 * </ul>
 *
 * @param productSku        the product asked about
 * @param availableQuantity units that can still be promised, zero when not tracked
 * @param reservedQuantity  units already promised to accepted orders
 * @param tracked           whether inventory tracks this SKU at all
 * @param asOf              when the figure was read
 */
public record StockAvailability(
        String productSku,
        int availableQuantity,
        int reservedQuantity,
        boolean tracked,
        Instant asOf) {

    static StockAvailability tracked(com.observability.lab.inventory.domain.StockLevel stock,
            Instant asOf) {
        return new StockAvailability(stock.getProductSku(), stock.getAvailableQuantity(),
                stock.getReservedQuantity(), true, asOf);
    }

    static StockAvailability untracked(String productSku, Instant asOf) {
        return new StockAvailability(productSku, 0, 0, false, asOf);
    }

    /** Whether the requested quantity could have been satisfied at {@link #asOf}. */
    public boolean sufficientFor(int requestedQuantity) {
        return tracked && requestedQuantity > 0 && availableQuantity >= requestedQuantity;
    }
}
