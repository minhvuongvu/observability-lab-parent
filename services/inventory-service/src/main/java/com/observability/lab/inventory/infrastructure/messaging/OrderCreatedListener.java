package com.observability.lab.inventory.infrastructure.messaging;

import com.observability.lab.inventory.application.ReservationLine;
import com.observability.lab.inventory.application.ReservationResult;
import com.observability.lab.inventory.application.StockApplicationService;
import com.observability.lab.shared.correlation.CorrelationContext;
import com.observability.lab.shared.correlation.CorrelationFields;
import com.observability.lab.shared.correlation.CorrelationHeaders;
import com.observability.lab.shared.correlation.ServiceIdentity;
import com.observability.lab.shared.exception.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Reacts to an accepted order by reserving the stock it needs.
 *
 * <p>The listener does three things and no more: establish the log context, validate that the
 * message is usable at all, and hand it to the use case. The reservation rules, the idempotency and
 * the transaction all live in the application layer, where they can be tested without a broker.
 */
@Component
public class OrderCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedListener.class);
    private static final String EVENT_TYPE = "order-created";

    private final StockApplicationService stock;
    private final ServiceIdentity identity;

    public OrderCreatedListener(StockApplicationService stock, ServiceIdentity identity) {
        this.stock = stock;
        this.identity = identity;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.order-created}",
            containerFactory = "orderCreatedListenerFactory")
    public void onOrderCreated(
            @Payload OrderCreatedMessage message,
            @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
            @Header(name = KafkaHeaders.OFFSET, required = false) Long offset,
            @Header(name = CorrelationHeaders.CORRELATION_ID, required = false) byte[] correlationId) {

        establishContext(message, correlationId);
        try {
            requireUsable(message);

            List<ReservationLine> lines = message.items().stream()
                    .map(item -> new ReservationLine(item.productSku(), item.quantity()))
                    .toList();

            ReservationResult result =
                    stock.reserveForOrder(message.eventId(), message.orderNumber(), lines);

            log.info("Handled {} for order '{}' from partition {} offset {}: {}",
                    EVENT_TYPE, message.orderNumber(), partition, offset, result.outcome());

            // Telling the Order Service the outcome — publishing inventory-updated so a PENDING
            // order can become CONFIRMED or REJECTED — is the integration step's work. Until then
            // the decision is recorded in Oracle and visible here, and orders stay PENDING.
        } finally {
            // The listener thread is pooled and long-lived. Without this, the next record on this
            // thread inherits the previous order's identity and the logs attribute one order's
            // work to another.
            CorrelationContext.clear();
        }
    }

    /**
     * Establishes the correlation context for this record.
     *
     * <p>A correlation header is honoured when present. The Order Service does not set one yet, so
     * the order number is used instead: it is the natural business correlation and makes the
     * producer's and consumer's log lines joinable on a field both already carry. Real trace context
     * propagation across the broker arrives with the tracing step, when the SDK owns the headers.
     */
    private void establishContext(OrderCreatedMessage message, byte[] correlationId) {
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
     * <p>A {@link ValidationException} is registered as non-retryable, so a structurally broken
     * message is logged and skipped rather than blocking the partition while it fails identically
     * three more times.
     */
    private static void requireUsable(OrderCreatedMessage message) {
        if (message.eventId() == null || message.eventId().isBlank()) {
            throw new ValidationException("order-created carried no eventId; cannot de-duplicate it.");
        }
        if (message.orderNumber() == null || message.orderNumber().isBlank()) {
            throw new ValidationException("order-created carried no orderNumber.");
        }
        if (message.items() == null || message.items().isEmpty()) {
            throw new ValidationException(
                    "order-created for order '" + message.orderNumber() + "' carried no items.");
        }
    }
}
