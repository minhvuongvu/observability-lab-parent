package com.observability.lab.order.infrastructure.client;

/**
 * Stock level as reported by the Inventory Service.
 *
 * <p>A copy of the other service's contract, owned by this service. Sharing a DTO module between the
 * two would couple their release cycles: neither could change its own API without recompiling the
 * other. A duplicated record is the cheaper trade.
 *
 * @param productSku        the product queried
 * @param availableQuantity units that can be reserved right now
 * @param reservedQuantity  units already committed to other orders
 */
public record StockLevelResponse(String productSku, int availableQuantity, int reservedQuantity) {}
