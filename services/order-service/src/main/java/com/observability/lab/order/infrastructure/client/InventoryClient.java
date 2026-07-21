package com.observability.lab.order.infrastructure.client;

import com.observability.lab.shared.api.ApiResponse;
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
 * <p>No {@code url}. The name is resolved through the Consul registry: Spring Cloud LoadBalancer
 * asks the discovery client for the healthy instances registered as {@code inventory-service} and
 * picks one per call. That is the point of having a registry — an instance can move, scale out or be
 * replaced without anything here being reconfigured or restarted, and an instance failing its health
 * check stops receiving traffic on its own.
 *
 * <p>A fixed URL would have made the registry decorative: the service would register itself and then
 * nothing would ever look it up.
 *
 * @see InventoryClientConfiguration for timeouts, retry policy and error translation
 */
@FeignClient(
        name = "inventory-service",
        configuration = InventoryClientConfiguration.class)
public interface InventoryClient {

    /**
     * Reads the current stock level for one product.
     *
     * <p>Returns the platform envelope rather than the payload directly. Every endpoint in this
     * platform answers with {@code {success, data, error, meta}}, so decoding straight into
     * {@link StockLevelResponse} does not fail — it quietly matches nothing and yields a record full
     * of zeros. A stock level that reads as zero because of a decoding mismatch is indistinguishable
     * from one that is genuinely zero, which is the kind of bug that reaches production.
     *
     * @param productSku the product to query
     * @throws com.observability.lab.shared.exception.ResourceNotFoundException when the SKU is unknown
     * @throws com.observability.lab.shared.exception.IntegrationException when the call fails
     */
    @GetMapping("/api/v1/stock/{productSku}")
    ApiResponse<StockLevelResponse> getStockLevel(@PathVariable("productSku") String productSku);
}
