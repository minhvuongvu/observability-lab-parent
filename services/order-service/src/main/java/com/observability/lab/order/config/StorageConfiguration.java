package com.observability.lab.order.config;

import com.observability.lab.order.infrastructure.storage.MinioProperties;
import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The object-storage client.
 *
 * <p>One client for the application. {@link MinioClient} is thread-safe and holds an HTTP connection
 * pool, so building one per upload would discard the pool along with it and open a fresh connection
 * every time.
 *
 * <p>No connectivity check at startup. The client is lazy, which is deliberate: an invoice archive
 * that is briefly unreachable should not stop the service from accepting orders, and the failure is
 * better reported by the upload that actually needed it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MinioProperties.class)
public class StorageConfiguration {

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }
}
