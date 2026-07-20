package com.observability.lab.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Producer wiring for order events.
 *
 * <p>Exists for one reason: to make the serializer use the application's own {@link ObjectMapper}.
 *
 * <p>Configuring {@code value-serializer} by class name leaves Kafka to instantiate
 * {@link JsonSerializer} through its no-argument constructor, which builds a private mapper. That
 * mapper registers the Java time module but leaves {@code WRITE_DATES_AS_TIMESTAMPS} enabled, so an
 * {@code Instant} goes onto the wire as {@code 1784537424.040746} while the same field in an HTTP
 * response renders as {@code "2026-07-20T08:50:24.040746Z"}.
 *
 * <p>One service, two representations of the same instant, is a bug waiting for whoever writes the
 * consumer. Passing Boot's configured mapper makes both channels agree.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfiguration {

    @Bean
    public ProducerFactory<String, Object> orderProducerFactory(
            KafkaProperties kafkaProperties, SslBundles sslBundles, ObjectMapper objectMapper) {

        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(objectMapper);
        // No producer class name in the record headers. Embedding it would force every consumer to
        // own a class of that exact fully-qualified name; the topic and its documented schema are
        // the contract instead.
        valueSerializer.setAddTypeInfo(false);

        return new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(sslBundles),
                new StringSerializer(),
                valueSerializer);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(
            ProducerFactory<String, Object> orderProducerFactory) {
        return new KafkaTemplate<>(orderProducerFactory);
    }
}
