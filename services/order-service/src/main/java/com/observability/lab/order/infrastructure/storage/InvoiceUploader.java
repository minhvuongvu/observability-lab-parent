package com.observability.lab.order.infrastructure.storage;

import com.observability.lab.order.application.InvoiceArchive;
import com.observability.lab.order.application.InvoiceRenderer;
import com.observability.lab.order.application.OrderApplicationService;
import com.observability.lab.order.application.OrderMetrics;
import com.observability.lab.order.application.OrderCreatedEvent;
import com.observability.lab.shared.logging.LogContext;
import com.observability.lab.shared.tracing.Spans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Archives an order's invoice once the order is durable.
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT}, unlike the outbox write, and for the opposite reason.
 * Uploading to object storage is a network call to another system; doing it inside the transaction
 * would hold a database connection open for the duration of an HTTP round trip and roll the order
 * back if the bucket were briefly unavailable. Neither is a trade worth making for a document that is
 * <em>derived</em> from the order.
 *
 * <p>That derivation is also why a failure here is logged rather than propagated. The invoice can be
 * rebuilt from the order at any time, and {@code OrderController} does exactly that when a caller
 * asks for one that is missing. Refusing the order because its receipt could not be filed would be
 * the wrong priority.
 */
@Component
public class InvoiceUploader {

    private static final Logger log = LoggerFactory.getLogger(InvoiceUploader.class);

    private final OrderApplicationService orders;
    private final InvoiceRenderer renderer;
    private final InvoiceArchive archive;
    private final OrderMetrics metrics;

    public InvoiceUploader(OrderApplicationService orders, InvoiceRenderer renderer,
            InvoiceArchive archive, OrderMetrics metrics) {
        this.orders = orders;
        this.renderer = renderer;
        this.archive = archive;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        try (var scope = LogContext.with("order_number", event.orderNumber())) {
            // Timed on both paths. A timer that only records successes reports a healthy latency
            // while every upload is failing fast, which is worse than no timer at all.
            var sample = metrics.startInvoiceUpload();
            try {
                // Read back rather than rendering from the event: the event carries what a consumer
                // needs to reserve stock, not what an invoice needs to show. This read is served by
                // the cache it also warms.
                archive.store(renderer.render(orders.findByOrderNumber(event.orderNumber())));
                metrics.recordInvoiceUpload(sample, true);
                log.info("Invoice archived for order '{}'", event.orderNumber());

            } catch (RuntimeException failure) {
                metrics.recordInvoiceUpload(sample, false);
                // A genuine fault, so it does get an error status and a recorded exception - which
                // is what makes it visible on the trace rather than only in the log file.
                Spans.failed("invoice upload failed", failure);
                log.error("Could not archive the invoice for order '{}'. The order stands; the "
                        + "invoice will be rebuilt when it is first requested.",
                        event.orderNumber(), failure);
            }
        }
    }
}
