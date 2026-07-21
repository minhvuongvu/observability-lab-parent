package com.observability.lab.order.infrastructure.storage;

import com.observability.lab.order.application.InvoiceArchive;
import com.observability.lab.order.application.InvoiceDocument;
import com.observability.lab.shared.exception.IntegrationException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps invoices in a MinIO bucket.
 *
 * <p>MinIO speaks S3, so nothing here is specific to it beyond the endpoint: the same code works
 * against S3 with a different URL and credentials, which is why object storage is worth using in a
 * lab rather than writing files to a local disk.
 *
 * <p>The service authenticates as a least-privilege user whose policy covers the invoice bucket and
 * nothing else — provisioned by {@code infrastructure/minio/init/create-buckets.sh}. The MinIO root
 * account administers the server and is never handed to an application.
 */
@Component
public class MinioInvoiceArchive implements InvoiceArchive {

    private static final Logger log = LoggerFactory.getLogger(MinioInvoiceArchive.class);

    /** Names this dependency in the error, its log fields and its metric tags. */
    private static final String DEPENDENCY = "minio";

    /** MinIO's own code for "that object is not there", as opposed to a genuine failure. */
    private static final String NO_SUCH_KEY = "NoSuchKey";

    /**
     * Uploaded in one part.
     *
     * <p>An invoice is kilobytes. Passing -1 for the object size would make the client buffer and
     * multipart-upload a stream of unknown length, which is the right behaviour for a large file and
     * pure overhead for this one.
     */
    private static final long NO_PART_SIZE = -1L;

    private final MinioClient client;
    private final String bucket;

    public MinioInvoiceArchive(MinioClient client, MinioProperties properties) {
        this.client = client;
        this.bucket = properties.bucket();
    }

    @Override
    public void store(InvoiceDocument invoice) {
        String objectName = objectNameFor(invoice.orderNumber());
        try (var content = new ByteArrayInputStream(invoice.content())) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(content, invoice.size(), NO_PART_SIZE)
                    .contentType(invoice.contentType())
                    .build());

            log.debug("Stored invoice {} ({} bytes) in bucket '{}'",
                    objectName, invoice.size(), bucket);

        } catch (Exception failure) {
            throw IntegrationException.failed(DEPENDENCY,
                    "could not store the invoice for order '" + invoice.orderNumber() + "'", failure);
        }
    }

    @Override
    public boolean contains(String orderNumber) {
        try {
            client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectNameFor(orderNumber))
                    .build());
            return true;

        } catch (ErrorResponseException absent) {
            // Only "not found" means absent. Any other error response — denied, bucket missing — is
            // a real fault, and reporting it as "no invoice" would hide a broken deployment behind
            // an empty result.
            if (NO_SUCH_KEY.equals(absent.errorResponse().code())) {
                return false;
            }
            throw IntegrationException.failed(DEPENDENCY,
                    "could not check the invoice for order '" + orderNumber + "'", absent);

        } catch (Exception failure) {
            throw IntegrationException.failed(DEPENDENCY,
                    "could not check the invoice for order '" + orderNumber + "'", failure);
        }
    }

    @Override
    public Optional<URI> temporaryUrl(String orderNumber, Duration validFor) {
        if (!contains(orderNumber)) {
            return Optional.empty();
        }
        try {
            // Signed locally from the credentials; no request reaches MinIO. The signature encodes
            // the object, the method and the expiry, so the URL grants exactly one read of one
            // object for a bounded time and cannot be edited into access to anything else.
            String url = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectNameFor(orderNumber))
                    .expiry((int) validFor.toSeconds(), TimeUnit.SECONDS)
                    .build());

            return Optional.of(URI.create(url));

        } catch (Exception failure) {
            throw IntegrationException.failed(DEPENDENCY,
                    "could not sign a URL for the invoice of order '" + orderNumber + "'", failure);
        }
    }

    /**
     * Where an order's invoice lives.
     *
     * <p>Derived from the order number rather than stored in the database. The invoice for an order
     * is at a known address, so there is no second source of truth to keep in step and no migration
     * needed to add object storage to an existing table.
     *
     * <p>The {@code orders/} prefix keeps the bucket navigable if it ever holds anything else; S3 has
     * no directories, but every console and client renders a slash as one.
     */
    private static String objectNameFor(String orderNumber) {
        return "orders/" + orderNumber + "/invoice.json";
    }
}
