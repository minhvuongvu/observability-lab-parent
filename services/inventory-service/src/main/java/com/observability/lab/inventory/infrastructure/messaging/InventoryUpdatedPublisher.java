package com.observability.lab.inventory.infrastructure.messaging;

import com.observability.lab.shared.correlation.CorrelationContext;
import com.observability.lab.shared.correlation.CorrelationHeaders;
import com.observability.lab.shared.exception.IntegrationException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Announces a reservation's outcome on {@code inventory-updated}.
 *
 * <p>Sends <strong>synchronously</strong>, and that is the design rather than an oversight. The
 * caller is the {@code order-created} listener, whose offset is committed only once it returns
 * normally. Waiting for the broker's acknowledgement therefore ties the two together: if the
 * announcement cannot be made, the listener throws, the offset is not committed, and Kafka redelivers
 * the order. The reservation itself is idempotent and its decision is recorded, so the redelivery
 * replays the same settlement instead of reserving again.
 *
 * <p>The alternative — firing and forgetting — would commit the offset for an order whose outcome
 * nobody ever heard, leaving it PENDING with its stock reserved and nothing to reconcile against.
 *
 * <p>This service does not use a transactional outbox for the reply, unlike the Order Service's
 * {@code order-created}. It does not need one: the consumer's offset is the durable marker, and
 * replaying the source event reproduces the reply exactly.
 */
@Component
public class InventoryUpdatedPublisher {

    private static final Logger log = LoggerFactory.getLogger(InventoryUpdatedPublisher.class);
    private static final String DEPENDENCY = "kafka";

    /** Bounded so a struggling broker stalls one listener thread, not the container indefinitely. */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public InventoryUpdatedPublisher(KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${app.kafka.topics.inventory-updated}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * Publishes one settlement and waits for it to be acknowledged.
     *
     * @throws IntegrationException when the broker did not accept the message, so the caller fails
     *                              and the source record is redelivered
     */
    public void publish(InventoryUpdatedMessage message) {
        // Keyed by order number so the settlement shares a partition with everything else about that
        // order, and the Order Service sees events for one order in the order they happened.
        ProducerRecord<String, Object> record =
                new ProducerRecord<>(topic, message.orderNumber(), message);

        // Propagated explicitly: a Kafka producer thread has no MDC, and without this the Order
        // Service's handling of the settlement could not be joined to the request that caused it.
        // The tracing step replaces this with the OpenTelemetry propagator.
        String correlationId = CorrelationContext.correlationId();
        if (correlationId != null) {
            record.headers().add(CorrelationHeaders.CORRELATION_ID,
                    correlationId.getBytes(StandardCharsets.UTF_8));
        }

        try {
            var result = kafkaTemplate.send(record).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            log.debug("Published inventory-updated ({}) for order '{}' to {}-{}@{}",
                    message.outcome(), message.orderNumber(),
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw IntegrationException.failed(DEPENDENCY,
                    "interrupted while publishing inventory-updated for order '"
                            + message.orderNumber() + "'", interrupted);

        } catch (Exception failure) {
            throw IntegrationException.failed(DEPENDENCY,
                    "could not publish inventory-updated for order '" + message.orderNumber() + "'",
                    failure);
        }
    }
}
