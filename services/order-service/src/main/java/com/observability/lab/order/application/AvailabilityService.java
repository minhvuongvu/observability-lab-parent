package com.observability.lab.order.application;

import com.observability.lab.order.infrastructure.client.InventoryClient;
import com.observability.lab.shared.exception.ResourceNotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Asks the Inventory Service what is available, synchronously.
 *
 * <p>The REST half of the integration, and the reason it exists alongside the event half: a
 * <em>question</em> cannot be answered by an event. Placing an order is asynchronous because the
 * Order Service must keep accepting orders while Inventory is down; asking "is this in stock right
 * now" has no useful asynchronous form, so it goes over HTTP and accepts the coupling.
 *
 * <p>The call resolves {@code inventory-service} through the Consul registry rather than a fixed
 * URL — see {@link InventoryClient} — so this is also what proves the registry is load-bearing
 * rather than decorative.
 *
 * <p>Advisory only. Nothing here reserves stock, and a caller that skips this check gets exactly the
 * same guarantees; the authoritative decision is the Inventory Service's response to
 * {@code order-created}.
 */
@Service
public class AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    private final InventoryClient inventory;

    public AvailabilityService(InventoryClient inventory) {
        this.inventory = inventory;
    }

    /**
     * Checks each requested line against current stock.
     *
     * <p>One call per SKU, sequentially. Honest for the handful of lines an order carries, and the
     * cost is visible in the trace rather than hidden behind a fan-out this lab has no need for.
     */
    public List<AvailabilityView> check(List<CreateOrderCommand.Line> lines) {
        return lines.stream().map(this::checkOne).toList();
    }

    private AvailabilityView checkOne(CreateOrderCommand.Line line) {
        try {
            var response = inventory.getStockLevel(line.productSku());
            var stock = response.data();
            if (stock == null) {
                // A 2xx whose envelope carries no payload. Treating it as "unknown" rather than as
                // zero available keeps a protocol surprise from reading like a stock-out.
                log.warn("Inventory returned no payload for '{}'", line.productSku());
                return AvailabilityView.unknown(line.productSku(), line.quantity());
            }
            return AvailabilityView.of(line.productSku(), line.quantity(), stock.availableQuantity());

        } catch (ResourceNotFoundException unknown) {
            // Translated by the Feign error decoder from a 404. Not a failure of this call.
            log.debug("Inventory does not track '{}'", line.productSku());
            return AvailabilityView.unknown(line.productSku(), line.quantity());
        }
    }
}
