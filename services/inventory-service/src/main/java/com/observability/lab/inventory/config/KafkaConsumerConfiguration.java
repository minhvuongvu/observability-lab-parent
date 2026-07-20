package com.observability.lab.inventory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.observability.lab.inventory.infrastructure.messaging.OrderCreatedMessage;
import com.observability.lab.shared.exception.BusinessException;
import com.observability.lab.shared.exception.ValidationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
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
                    KafkaProperties kafkaProperties) {

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
        factory.setCommonErrorHandler(orderCreatedErrorHandler());
        return factory;
    }

    private DefaultErrorHandler orderCreatedErrorHandler() {
        DefaultErrorHandler handler =
                new DefaultErrorHandler(new FixedBackOff(RETRY_INTERVAL_MS, RETRY_ATTEMPTS));

        // A rule that refused once refuses identically every time. Retrying it wastes the budget
        // and holds up every later record on the same partition.
        handler.addNotRetryableExceptions(BusinessException.class, ValidationException.class);

        // Once the attempts are exhausted the record is logged and skipped so the partition keeps
        // moving. Routing it to dead-letter-topic instead — where it can be inspected rather than
        // merely lost — belongs with the retry and DLQ work of the integration step.
        return handler;
    }
}
