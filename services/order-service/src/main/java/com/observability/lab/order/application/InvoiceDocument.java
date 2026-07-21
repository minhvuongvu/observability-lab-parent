package com.observability.lab.order.application;

import java.nio.charset.StandardCharsets;

/**
 * A rendered invoice, ready to be stored.
 *
 * <p>Bytes rather than a domain object: by the time an invoice reaches storage it is a finished
 * artefact, and what makes it correct is that it does <em>not</em> change when the order later does.
 * An invoice regenerated from a mutated order would silently rewrite history.
 *
 * @param orderNumber the order this invoices, and the only thing storage needs in order to name it
 * @param contentType media type stored alongside the object, so a browser opening the signed URL
 *                    renders it instead of downloading an opaque blob
 * @param content     the document itself
 */
public record InvoiceDocument(String orderNumber, String contentType, byte[] content) {

    /** Convenience for the common case of a UTF-8 text rendering. */
    public static InvoiceDocument text(String orderNumber, String contentType, String body) {
        return new InvoiceDocument(orderNumber, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    public int size() {
        return content.length;
    }
}
