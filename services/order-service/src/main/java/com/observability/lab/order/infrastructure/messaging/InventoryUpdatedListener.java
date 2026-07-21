package com.observability.lab.order.infrastructure.messaging;

import com.observability.lab.order.application.OrderApplicationService;
import com.observability.lab.shared.correlation.CorrelationContext;
import com.observability.lab.shared.correlation.CorrelationFields;
import com.observability.lab.shared.correlation.CorrelationHeaders;
import com.observability.lab.shared.correlation.ServiceIdentity;
import com.observability.lab.shared.exception.ValidationException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Closes the order flow: applies the reservation verdict to the order it concerns.
 *
 * <p>The listener establishes the log context, rejects a message it cannot act on, and hands the
 * decision to the use case. Whether a given transition is legal, and what to do when it is not, is
 * the application layer's business — see {@code OrderApplicationService.settle}.
 *
 * <p>No de-duplication table on this side. Settling is naturally idempotent: moving an order to a
 * state it already occupies is a no-op, so a redelivery costs one query and changes nothing. The
 * Inventory Service needs a {@code processed_events} table because reserving stock twice genuinely
 * subtracts twice; assigning a status twice does not.
 */
@Component
public class InventoryUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryUpdatedListener.class);
    private static final String EVENT_TYPE = "inventory-updated";

    private final OrderApplicationService orders;
    private final ServiceIdentity identity;

    public InventoryUpdatedListener(OrderApplicationService orders, ServiceIdentity identity) {
        this.orders = orders;
        this.identity = identity;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.inventory-updated}",
            containerFactory = "inventoryUpdatedListenerFactory")
    public void onInventoryUpdated(
            @Payload InventoryUpdatedMessage message,
            @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
            @Header(name = KafkaHeaders.OFFSET, required = false) Long offset,
            @Header(name = CorrelationHeaders.CORRELATION_ID, required = false) byte[] correlationId) {

        establishContext(message, correlationId);
        try {
            requireUsable(message);

            var settled = orders.settle(message.orderNumber(), message.isReserved());

            if (message.isReserved()) {
                log.info("Handled {} for order '{}' from partition {} offset {}: order is now {}",
                        EVENT_TYPE, message.orderNumber(), partition, offset, settled.status());
            } else {
                // INFO rather than ERROR: a refused order is this system working. The shortages are
                // included because "why was it rejected" is the next question anyone will ask.
                log.info("Handled {} for order '{}' from partition {} offset {}: order is now {} ({})",
                        EVENT_TYPE, message.orderNumber(), partition, offset, settled.status(),
                        message.shortages());
            }
        } finally {
            // The listener thread is pooled and long-lived. Without this the next record inherits
            // the previous order's identity and the logs attribute one order's work to another.
            CorrelationContext.clear();
        }
    }

    /**
     * Establishes the correlation context for this record.
     *
     * <p>The Inventory Service propagates a correlation id explicitly, so the whole round trip —
     * request, reservation, settlement — shares one identifier. Falling back to the order number
     * keeps the two sides joinable even for a message published before that was in place.
     */
    private void establishContext(InventoryUpdatedMessage message, byte[] correlationId) {
        CorrelationContext.requestId(message.eventId());
        CorrelationContext.correlationId(correlationId != null
                ? new String(correlationId, StandardCharsets.UTF_8)
                : message.orderNumber());
        CorrelationContext.put(CorrelationFields.SERVICE, identity.name());
        CorrelationContext.put(CorrelationFields.ENVIRONMENT, identity.environment());
        CorrelationContext.put(CorrelationFields.VERSION, identity.version());
    }

    /**
     * Rejects a message that cannot be acted on at all.
     *
     * <p>{@link ValidationException} is registered as non-retryable, so a structurally broken message
     * goes straight to the dead-letter topic instead of failing identically three more times while
     * every later record on the partition waits behind it.
     */
    private static void requireUsable(InventoryUpdatedMessage message) {
        if (message.orderNumber() == null || message.orderNumber().isBlank()) {
            throw new ValidationException("inventory-updated carried no orderNumber.");
        }
        if (message.outcome() == null || message.outcome().isBlank()) {
            throw new ValidationException("inventory-updated for order '" + message.orderNumber()
                    + "' carried no outcome.");
        }
    }
}
