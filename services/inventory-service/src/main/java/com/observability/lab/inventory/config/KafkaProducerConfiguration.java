package com.observability.lab.inventory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Producer wiring for {@code inventory-updated} and for dead letters.
 *
 * <p>Uses the application's own {@link ObjectMapper} for the same reason the consumer does: a
 * serializer built by Kafka from a class name gets a private mapper that renders {@code Instant} as
 * an epoch decimal, so the timestamp on the wire disagrees with the one this service's HTTP API
 * produces. One service must not have two representations of the same instant.
 *
 * <p>The template is typed to {@code Object} rather than to the message record because it carries
 * two unrelated things: settlements, and whatever a dead letter turns out to contain.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaProducerConfiguration {

    @Bean
    public ProducerFactory<String, Object> inventoryProducerFactory(
            KafkaProperties kafkaProperties, SslBundles sslBundles, ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {

        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(objectMapper);
        // No producer class name in the headers: the topic and its documented schema are the
        // contract, not a fully-qualified Java class the consumer would then have to own.
        valueSerializer.setAddTypeInfo(false);

        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(sslBundles),
                new StringSerializer(),
                valueSerializer);

        // Declaring a custom ProducerFactory bean makes Boot's auto-configuration back off, taking
        // its Micrometer listener with it. Without this the Kafka client's own metrics are silently
        // absent and the gap only shows up as a permanently empty dashboard panel.
        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(
            ProducerFactory<String, Object> inventoryProducerFactory) {
        return new KafkaTemplate<>(inventoryProducerFactory);
    }
}
