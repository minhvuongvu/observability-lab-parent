package com.observability.lab.order.infrastructure.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Synchronous read access to the Inventory Service.
 *
 * <p>The counterpart to the asynchronous path. Stock changes travel as Kafka events because the
 * Order Service must keep working when Inventory is down; a stock <em>query</em> cannot be answered
 * from an event, so it goes over HTTP and accepts the coupling that comes with it.
 *
 * <p>The target is configured statically for now. Once services register themselves, the URL
 * disappears and Feign resolves {@code inventory-service} through the registry instead.
 *
 * @see InventoryClientConfiguration for timeouts, retry policy and error translation
 */
@FeignClient(
        name = "inventory-service",
        url = "${app.clients.inventory.url}",
        configuration = InventoryClientConfiguration.class)
public interface InventoryClient {

    /**
     * Reads the current stock level for one product.
     *
     * @param productSku the product to query
     * @throws com.observability.lab.shared.exception.ResourceNotFoundException when the SKU is unknown
     * @throws com.observability.lab.shared.exception.IntegrationException when the call fails
     */
    @GetMapping("/api/v1/stock/{productSku}")
    StockLevelResponse getStockLevel(@PathVariable("productSku") String productSku);
}
