package com.observability.lab.order.infrastructure.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Connection and policy settings for the invoice bucket.
 *
 * <p>Bound and validated at startup, so a missing endpoint or a blank secret fails the context rather
 * than the first upload. The credentials come from the environment, never from a tracked file.
 *
 * <p>How long a signed URL stays valid is deliberately not here: that is a policy about how the
 * application hands out invoices, not a detail of how it connects to a bucket, so it lives under
 * {@code app.invoice.url-validity} and is passed in by the use case.
 *
 * @param endpoint  MinIO S3 API address
 * @param accessKey least-privilege application user, not the MinIO root account
 * @param secretKey that user's secret
 * @param bucket    where invoices live
 */
@Validated
@ConfigurationProperties(prefix = "app.storage.minio")
public record MinioProperties(
        @NotBlank String endpoint,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotBlank String bucket) {
}
