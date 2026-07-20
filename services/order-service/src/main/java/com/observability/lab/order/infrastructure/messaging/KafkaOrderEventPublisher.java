package com.observability.lab.order.infrastructure.messaging;

import com.observability.lab.order.application.OrderCreatedEvent;
import com.observability.lab.shared.correlation.CorrelationContext;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Forwards accepted orders onto the {@code order-created} topic.
 *
 * <p>Listening {@link TransactionPhase#AFTER_COMMIT} rather than sending from the use case is the
 * whole point of this class. Sending inside the transaction announces orders that may still roll
 * back, and a consumer that reserves stock for an order which never existed has no way to find out.
 *
 * <p>The remaining gap is the reverse: the transaction commits and the send then fails, leaving an
 * order with no event. That is the dual-write problem, and the honest fix is a transactional outbox
 * — the event is written to the database in the same transaction and relayed separately. This class
 * logs the failure loudly; the outbox arrives with the integration step.
 */
@Component
public class KafkaOrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaOrderEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public KafkaOrderEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${app.kafka.topics.order-created}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        // The send completes on a Kafka producer thread, which has no MDC of its own. Capturing the
        // context here and restoring it in the callback is what keeps the success or failure log
        // line attached to the request that caused it.
        Map<String, String> correlation = CorrelationContext.snapshot();

        // Keyed by order number so every event for one order lands on the same partition and is
        // therefore delivered in order. Keying by anything else would let a cancellation overtake
        // the creation it refers to.
        kafkaTemplate.send(topic, event.orderNumber(), event)
                .whenComplete((result, failure) -> {
                    Map<String, String> previous = CorrelationContext.snapshot();
                    CorrelationContext.restore(correlation);
                    try {
                        if (failure != null) {
                            log.error("Failed to publish order-created for order '{}' to topic '{}'. "
                                            + "The order is committed but no consumer will hear about it.",
                                    event.orderNumber(), topic, failure);
                        } else {
                            log.debug("Published order-created for order '{}' to {}-{}@{}",
                                    event.orderNumber(),
                                    result.getRecordMetadata().topic(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    } finally {
                        CorrelationContext.restore(previous);
                    }
                });
    }
}
