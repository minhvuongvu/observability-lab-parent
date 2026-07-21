package com.observability.lab.inventory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.observability.lab.inventory.infrastructure.messaging.OrderCreatedMessage;
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
 * Consumer wiring for {@code order-created}.
 *
 * <p>Three decisions here matter more than the rest of the file.
 *
 * <p><strong>The deserialiser uses the application's own {@link ObjectMapper}.</strong> Naming the
 * deserialiser by class in {@code application.yml} would leave Kafka to build a private mapper that
 * cannot read the ISO-8601 timestamps the producer writes. The same trap on the producing side put
 * epoch decimals on the wire; this is the other half of it.
 *
 * <p><strong>Deserialisation errors do not reach the poll loop.</strong> An unparseable record —
 * malformed JSON, a truncated payload, a message from an unrelated producer — throws inside the
 * consumer before any listener runs. Without {@link ErrorHandlingDeserializer} that exception is
 * raised on every poll of the same offset, forever: the consumer never advances, lag grows without
 * limit, and no listener code is involved to notice. Wrapping it turns the failure into a record the
 * error handler can deal with.
 *
 * <p><strong>Business refusals are not retried.</strong> Retrying "there is not enough stock" simply
 * produces the same answer three more times and delays every subsequent record on the partition.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfiguration {

    /** Three attempts, one second apart, for failures that might genuinely be transient. */
    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long RETRY_ATTEMPTS = 3L;

    @Bean
    public ConsumerFactory<String, OrderCreatedMessage> orderCreatedConsumerFactory(
            KafkaProperties kafkaProperties, SslBundles sslBundles, ObjectMapper objectMapper) {

        // false: ignore any type header and always deserialise to this type. The producer does not
        // send one, and trusting a class name from the wire would let a producer choose which class
        // this service instantiates.
        JsonDeserializer<OrderCreatedMessage> valueDeserializer =
                new JsonDeserializer<>(OrderCreatedMessage.class, objectMapper, false);

        return new DefaultKafkaConsumerFactory<>(
                kafkaProperties.buildConsumerProperties(sslBundles),
                new StringDeserializer(),
                new ErrorHandlingDeserializer<>(valueDeserializer));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderCreatedMessage>
            orderCreatedListenerFactory(
                    ConsumerFactory<String, OrderCreatedMessage> orderCreatedConsumerFactory,
                    KafkaProperties kafkaProperties,
                    DefaultErrorHandler orderCreatedErrorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, OrderCreatedMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(orderCreatedConsumerFactory);

        KafkaProperties.Listener listener = kafkaProperties.getListener();
        if (listener.getConcurrency() != null) {
            factory.setConcurrency(listener.getConcurrency());
        }
        if (listener.getAckMode() != null) {
            factory.getContainerProperties().setAckMode(listener.getAckMode());
        }
        factory.setCommonErrorHandler(orderCreatedErrorHandler);
        return factory;
    }

    /**
     * Retry with backoff, then dead-letter.
     *
     * <p>The recoverer runs only after the attempts are exhausted. It republishes the original record
     * — key, value and headers intact — to {@code dead-letter-topic}, and Spring adds headers naming
     * the original topic, partition, offset and the exception that finished it off. That is the
     * difference between a message that can be diagnosed and one that was merely logged: the payload
     * is still there to be replayed once the cause is fixed.
     *
     * <p>Nothing consumes the dead-letter topic. A queue that automatically feeds poison messages
     * back into the consumer that already choked on them is a loop, not a recovery.
     */
    @Bean
    public DefaultErrorHandler orderCreatedErrorHandler(KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${app.kafka.topics.dead-letter}") String deadLetterTopic) {

        // Same partition on the dead-letter topic as the record had on its source topic, so
        // per-key ordering survives the move and the two are comparable when investigating.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(deadLetterTopic, record.partition()));

        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(RETRY_INTERVAL_MS, RETRY_ATTEMPTS));

        // A rule that refused once refuses identically every time. Retrying it wastes the budget
        // and holds up every later record on the same partition, so these go straight to the
        // dead-letter topic on the first failure.
        handler.addNotRetryableExceptions(BusinessException.class, ValidationException.class);

        handler.setLogLevel(KafkaException.Level.ERROR);
        return handler;
    }
}
