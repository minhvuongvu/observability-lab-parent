package com.observability.lab.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.observability.lab.order.infrastructure.messaging.InventoryUpdatedMessage;
import com.observability.lab.shared.exception.BusinessException;
import com.observability.lab.shared.exception.ValidationException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer wiring for {@code inventory-updated}.
 *
 * <p>The mirror image of the Inventory Service's consumer, and for the same reasons.
 *
 * <p><strong>The deserialiser uses the application's own {@link ObjectMapper}.</strong> Naming it by
 * class in {@code application.yml} would leave Kafka to build a private mapper that cannot read the
 * ISO-8601 timestamps the producer writes.
 *
 * <p><strong>Deserialisation errors do not reach the poll loop.</strong> An unparseable record throws
 * inside the consumer before any listener runs; without {@link ErrorHandlingDeserializer} that
 * exception is raised on every poll of the same offset forever, and no listener code is involved to
 * notice. Wrapping it turns the failure into a record the error handler can dead-letter.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfiguration {

    /** Three attempts, one second apart, for failures that might genuinely be transient. */
    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long RETRY_ATTEMPTS = 3L;

    @Bean
    public ConsumerFactory<String, InventoryUpdatedMessage> inventoryUpdatedConsumerFactory(
            KafkaProperties kafkaProperties, SslBundles sslBundles, ObjectMapper objectMapper) {

        // false: ignore any type header and always deserialise to this type. Trusting a class name
        // from the wire would let a producer choose which class this service instantiates.
        JsonDeserializer<InventoryUpdatedMessage> valueDeserializer =
                new JsonDeserializer<>(InventoryUpdatedMessage.class, objectMapper, false);

        return new DefaultKafkaConsumerFactory<>(
                kafkaProperties.buildConsumerProperties(sslBundles),
                new StringDeserializer(),
                new ErrorHandlingDeserializer<>(valueDeserializer));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, InventoryUpdatedMessage>
            inventoryUpdatedListenerFactory(
                    ConsumerFactory<String, InventoryUpdatedMessage> inventoryUpdatedConsumerFactory,
                    KafkaProperties kafkaProperties,
                    DefaultErrorHandler inventoryUpdatedErrorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, InventoryUpdatedMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(inventoryUpdatedConsumerFactory);

        KafkaProperties.Listener listener = kafkaProperties.getListener();
        if (listener.getConcurrency() != null) {
            factory.setConcurrency(listener.getConcurrency());
        }
        if (listener.getAckMode() != null) {
            factory.getContainerProperties().setAckMode(listener.getAckMode());
        }
        factory.setCommonErrorHandler(inventoryUpdatedErrorHandler);
        return factory;
    }

    /**
     * Retry with backoff, then dead-letter.
     *
     * <p>Republishes the original record — key, value and headers intact — to
     * {@code dead-letter-topic}, with headers naming the source topic, partition, offset and the
     * exception that finished it off. A settlement that cannot be applied is a genuine problem: the
     * order is stranded in {@code PENDING} with stock reserved against it, and the payload has to
     * survive for anyone to put that right.
     *
     * <p>Nothing consumes the dead-letter topic automatically.
     */
    @Bean
    public DefaultErrorHandler inventoryUpdatedErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.kafka.topics.dead-letter}") String deadLetterTopic) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(deadLetterTopic, record.partition()));

        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(RETRY_INTERVAL_MS, RETRY_ATTEMPTS));

        // A rule that refused once refuses identically every time, so these skip the retries and
        // go straight to the dead-letter topic rather than blocking the partition.
        handler.addNotRetryableExceptions(BusinessException.class, ValidationException.class);

        handler.setLogLevel(KafkaException.Level.ERROR);
        return handler;
    }
}
