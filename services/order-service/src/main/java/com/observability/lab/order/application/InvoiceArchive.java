package com.observability.lab.order.application;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Where invoices are kept.
 *
 * <p>A port, so the use cases depend on "an invoice can be stored and later handed out" rather than
 * on MinIO. The adapter lives in {@code infrastructure.storage}.
 *
 * <p>Note what is deliberately absent: any method returning the invoice bytes. The service does not
 * proxy downloads. It issues a time-limited URL and steps out of the way, which keeps large binary
 * transfers off the application's threads and connection pool entirely.
 */
public interface InvoiceArchive {

    /**
     * Stores an invoice, replacing any previous version of it.
     *
     * <p>Idempotent by object name: re-storing the invoice for the same order is how a failed upload
     * is repaired, so it must not accumulate duplicates. The bucket has versioning enabled, so the
     * previous rendering remains recoverable.
     */
    void store(InvoiceDocument invoice);

    /** Whether an invoice for this order has been stored. */
    boolean contains(String orderNumber);

    /**
     * A URL that grants temporary read access to one invoice.
     *
     * <p>Signed and expiring rather than a public bucket: an invoice names a customer and what they
     * bought, and object storage that is readable without credentials is one of the more reliable
     * ways to disclose that at scale.
     *
     * @return empty when no invoice has been stored for this order
     */
    Optional<URI> temporaryUrl(String orderNumber, Duration validFor);
}
