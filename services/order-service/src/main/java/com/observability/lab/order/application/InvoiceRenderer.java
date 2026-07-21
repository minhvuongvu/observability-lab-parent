package com.observability.lab.order.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.observability.lab.shared.exception.TechnicalException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns an order into the invoice document that gets archived.
 *
 * <p>JSON rather than a PDF. Producing a real PDF would add a rendering library and a page layout to
 * a project whose subject is operating a distributed system, and would teach nothing that this does
 * not: an artefact is derived, uploaded to object storage, and later handed out through a signed URL.
 *
 * <p>The invoice is a <em>snapshot</em>. It records what the order looked like when it was invoiced,
 * including the status at that moment, and is not updated when the order later changes.
 */
@Component
public class InvoiceRenderer {

    /** Rendered as JSON so a browser following the signed URL displays it rather than downloading it. */
    public static final String CONTENT_TYPE = "application/json";

    private final ObjectMapper objectMapper;

    public InvoiceRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public InvoiceDocument render(OrderView order) {
        Invoice invoice = new Invoice(
                "INV-" + order.orderNumber(),
                order.orderNumber(),
                order.customerId(),
                order.status().name(),
                order.currency(),
                order.totalAmount(),
                order.items().stream()
                        .map(line -> new Line(line.productSku(), line.quantity(),
                                line.unitPrice(), line.lineTotal()))
                        .toList(),
                order.createdAt(),
                Instant.now());

        try {
            // Pretty-printed on purpose: this object is meant to be opened and read by a human
            // poking at the MinIO console, which is the entire point of it existing in this lab.
            return new InvoiceDocument(order.orderNumber(), CONTENT_TYPE,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(invoice));
        } catch (JsonProcessingException exception) {
            throw new TechnicalException(
                    "Could not render the invoice for order '" + order.orderNumber() + "'.", exception);
        }
    }

    /** The archived document's shape. Separate from {@link OrderView} because it must not drift with it. */
    private record Invoice(
            String invoiceNumber,
            String orderNumber,
            String customerId,
            String orderStatus,
            String currency,
            BigDecimal totalAmount,
            List<Line> lines,
            Instant orderedAt,
            Instant issuedAt) {}

    private record Line(String productSku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}
}
