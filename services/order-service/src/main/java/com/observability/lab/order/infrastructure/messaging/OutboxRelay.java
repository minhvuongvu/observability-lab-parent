package com.observability.lab.order.infrastructure.messaging;

import com.observability.lab.order.application.OrderMetrics;
import com.observability.lab.order.domain.OutboxEvent;
import com.observability.lab.order.infrastructure.persistence.OutboxEventJpaRepository;
import com.observability.lab.shared.correlation.CorrelationContext;
import com.observability.lab.shared.correlation.CorrelationFields;
import com.observability.lab.shared.correlation.CorrelationHeaders;
import com.observability.lab.shared.correlation.ServiceIdentity;
import com.observability.lab.shared.logging.LogContext;
import com.observability.lab.shared.tracing.Spans;
import io.opentelemetry.api.trace.Span;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves durable outbox rows onto Kafka.
 *
 * <p>The other half of the outbox pattern. {@link OutboxOrderEventPublisher} guarantees the event
 * exists; this guarantees it eventually reaches the broker. Because the row survives, a failure here
 * is never data loss — the next run picks it up again. That is what turns "the broker was down when
 * the order was placed" from a lost event into a few seconds of delay.
 *
 * <p>Delivery is therefore <strong>at-least-once</strong>: a send that succeeds but whose
 * acknowledgement is lost is retried, and the consumer sees the event twice. Consumers de-duplicate
 * on {@code eventId} — see the Inventory Service's {@code ProcessedEvent}. Exactly-once across a
 * database and a broker is not available at any price worth paying here.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * How long a single send may take before the relay gives up on it for this run.
     *
     * <p>Bounded because the batch is claimed under a row lock: an unbounded wait would hold those
     * rows, and the database connection, for as long as the broker stays unreachable.
     */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final OutboxEventJpaRepository outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ServiceIdentity identity;
    private final OrderMetrics metrics;
    private final int batchSize;
    private final Duration retention;

    public OutboxRelay(OutboxEventJpaRepository outbox, KafkaTemplate<String, String> kafkaTemplate,
            ServiceIdentity identity, OrderMetrics metrics,
            @Value("${app.outbox.batch-size}") int batchSize,
            @Value("${app.outbox.retention}") Duration retention) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.identity = identity;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.retention = retention;
    }

    /**
     * Drains a batch of pending events.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}: the delay is measured from the end of the
     * previous run, so a slow run against a struggling broker cannot cause runs to overlap and
     * contend for the same rows.
     *
     * <p>Transactional so the claim lock, the sends and the resulting status updates are one unit.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outbox.claimUnpublished(PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            // Still refresh the gauge: an empty batch is the normal case, and it is how the backlog
            // gets reported as zero rather than staying at its last non-zero reading forever.
            metrics.outboxBacklog(0);
            return;
        }

        // A long task timer, so a pass wedged against an unreachable broker is visible *while* it is
        // stuck. A plain timer records nothing until the work finishes, which is precisely the wrong
        // behaviour for the failure it would be used to detect.
        var pass = metrics.startRelayPass();
        int published = 0;
        try {
            for (OutboxEvent event : batch) {
                if (publish(event)) {
                    published++;
                }
            }
        } finally {
            pass.stop();
        }

        long stillPending = outbox.countUnpublished();
        metrics.outboxBacklog(stillPending);

        if (published < batch.size()) {
            log.warn("Outbox relay published {} of {} claimed event(s); {} still pending overall",
                    published, batch.size(), stillPending);
        } else {
            log.debug("Outbox relay published {} event(s)", published);
        }
    }

    /**
     * Sends one event and records the outcome on its row.
     *
     * <p>Waits for the broker's acknowledgement rather than firing and forgetting. The whole purpose
     * of the row is to know whether the send actually happened, and a template future that has not
     * completed cannot answer that.
     */
    private boolean publish(OutboxEvent event) {
        // Restore the context the event was produced in, so this thread's log lines and the header
        // on the outbound record both carry it. Cleared afterwards: the scheduler thread is
        // long-lived and the next event in the batch belongs to a different transaction.
        CorrelationContext.correlationId(event.getCorrelationId());
        CorrelationContext.traceId(event.getTraceId());

        // Service identity too, not just the correlation ids. A scheduler thread has no filter to
        // establish it, and a log line without a `service` field is invisible to every query and
        // dashboard panel that filters by service — including the Kafka client's own lines, which
        // are emitted on this thread.
        CorrelationContext.put(CorrelationFields.SERVICE, identity.name());
        CorrelationContext.put(CorrelationFields.ENVIRONMENT, identity.environment());
        CorrelationContext.put(CorrelationFields.VERSION, identity.version());

        try (var scope = LogContext.with("event_id", event.getEventId())
                .and("order_number", event.getMessageKey())) {
            try {
                // A *link*, not a parent. This publish is caused by a request that finished minutes
                // ago; parenting to it would produce a trace with a minutes-long gap and a parent
                // that ended before its child began. A link records the causality and leaves both
                // timelines honest. See Spans.remoteContext.
                var origin = Spans.remoteContext(event.getTraceId(), event.getSpanId());
                if (origin.isValid()) {
                    Span.current().addLink(origin);
                }
                Spans.attribute(Spans.ORDER_NUMBER, event.getMessageKey());
                Spans.attribute(Spans.EVENT_ID, event.getEventId());

                ProducerRecord<String, String> record = new ProducerRecord<>(
                        event.getTopic(), event.getMessageKey(), event.getPayload());

                // The consumer reads this to continue the same business transaction. Without it the
                // Inventory Service invents its own correlation id and the two halves of one order
                // cannot be joined in Loki.
                if (event.getCorrelationId() != null) {
                    record.headers().add(CorrelationHeaders.CORRELATION_ID,
                            event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
                }

                var result = kafkaTemplate.send(record)
                        .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

                event.markPublished();
                log.debug("Relayed {} to {}-{}@{}", event.getEventType(),
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                return true;

            } catch (InterruptedException interrupted) {
                // Restoring the flag and stopping is the only correct response: swallowing it would
                // leave a thread that has been asked to stop running on regardless.
                Thread.currentThread().interrupt();
                event.markFailed("Interrupted while publishing.");
                return false;

            } catch (Exception failure) {
                event.markFailed(failure.toString());
                Spans.failed("outbox delivery failed", failure);
                log.warn("Outbox delivery of {} failed (attempt {}); it stays queued and will be retried",
                        event.getEventType(), event.getAttempts(), failure);
                return false;
            }
        } finally {
            CorrelationContext.clear();
        }
    }

    /**
     * Prunes delivered events.
     *
     * <p>Runs far less often than the relay: this is housekeeping, and a delete that scans the table
     * has no business competing with the delivery path for locks.
     */
    @Scheduled(fixedDelayString = "${app.outbox.cleanup-interval}",
            initialDelayString = "${app.outbox.cleanup-interval}")
    @Transactional
    public void pruneDelivered() {
        int removed = outbox.deletePublishedBefore(Instant.now().minus(retention));
        if (removed > 0) {
            log.info("Pruned {} delivered outbox event(s) older than {}", removed, retention);
        }
    }
}
