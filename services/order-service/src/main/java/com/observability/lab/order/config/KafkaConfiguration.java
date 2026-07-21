package com.observability.lab.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.MicrometerProducerListener;

/**
 * Producer wiring for order events.
 *
 * <p>Values are sent as strings, not objects, because this service publishes exclusively through the
 * transactional outbox: the payload was already rendered to JSON — with the application's own
 * {@link ObjectMapper} — when the row was written, inside the producing transaction. The relay's job
 * is to put those exact bytes on the wire.
 *
 * <p>A JSON-serialising template here would re-encode an already-encoded string, and consumers would
 * receive a quoted string containing escaped JSON rather than the document itself.
 *
 * <p>Rendering upstream also keeps a serialisation failure inside the transaction that caused it,
 * where it can abort the order, rather than surfacing minutes later on a scheduler thread with the
 * order already committed.
 *
     * <p>The producer factory carries a {@link MicrometerProducerListener}. Spring Boot adds one
     * automatically to the factory <em>it</em> builds, but declaring a custom {@code ProducerFactory}
     * bean makes that auto-configuration back off entirely — so without this line the Kafka client's
     * own metrics (batch size, record send rate, request latency, buffer exhaustion) are silently
     * absent, and the gap is only noticed when a dashboard panel is permanently empty.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfiguration {

    @Bean
    public ProducerFactory<String, String> orderProducerFactory(
            KafkaProperties kafkaProperties, SslBundles sslBundles, MeterRegistry meterRegistry) {

        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(sslBundles),
                new StringSerializer(),
                new StringSerializer());

        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(
            ProducerFactory<String, String> orderProducerFactory) {
        return new KafkaTemplate<>(orderProducerFactory);
    }
}
