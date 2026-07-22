package com.observability.lab.order.config;

import com.observability.lab.order.application.AvailabilityService;
import com.observability.lab.order.application.CreateOrderCommand;
import com.observability.lab.shared.chaos.DatabaseChaos;
import com.observability.lab.shared.chaos.MessagingChaos;
import com.observability.lab.shared.chaos.ResilienceChaos;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The Order Service's half of the chaos surface (step 17).
 *
 * <p>The shared library owns everything that is the same in every service — latency, CPU, memory,
 * deadlocks, log bursts. What is left is what only this service can do, because only this service has
 * a PostgreSQL connection, an outbox, and a circuit breaker in front of Inventory.
 *
 * <p>Guarded identically to the shared half: {@code local} and {@code dev} only, and switchable off
 * with {@code app.chaos.enabled}. Under {@code prod} these beans do not exist, so the corresponding
 * endpoints report themselves unsupported rather than failing at runtime.
 */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "dev"})
@ConditionalOnProperty(prefix = "app.chaos", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderChaosConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OrderChaosConfiguration.class);

    /**
     * PostgreSQL's sleep, holding a real pool connection for the duration.
     *
     * <p>{@code pg_sleep} rather than a slow application loop, because the connection has to be
     * genuinely occupied at the database end. A thread that merely held a Java object would exhaust
     * the pool without any of the server-side symptoms — no long-running query in {@code
     * pg_stat_activity}, nothing for {@code postgres-exporter} to report — and half the lesson is
     * seeing which side of the connection the evidence appears on.
     */
    @Bean
    public DatabaseChaos orderDatabaseChaos(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new DatabaseChaos() {
            @Override
            public void sleepInDatabase(Duration duration) {
                // queryTimeout is left at the default on purpose: the point is that the statement
                // runs to completion and holds the connection, not that the driver gives up early.
                jdbc.execute("SELECT pg_sleep(" + duration.toMillis() / 1000.0d + ")");
            }

            @Override
            public String databaseName() {
                return "PostgreSQL (orderdb)";
            }
        };
    }

    /**
     * Kafka faults on the topic this service produces to and the one it consumes.
     *
     * <p>The poison message is published to {@code inventory-updated}, which this service's own
     * listener consumes — so the retry policy, the backoff and the dead-letter recoverer configured
     * in {@link KafkaConsumerConfiguration} are all exercised for real. It is deliberately not
     * published to {@code order-created}: that topic is consumed by the Inventory Service, and a
     * scenario that dead-letters someone else's messages tests the wrong service's configuration.
     */
    @Bean
    public MessagingChaos orderMessagingChaos(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.kafka.topics.inventory-updated}") String inventoryUpdatedTopic,
            @Value("${app.kafka.topics.dead-letter}") String deadLetterTopic) {

        return new MessagingChaos() {
            @Override
            public String publishPoisonMessage() {
                String key = "chaos-poison-" + UUID.randomUUID();
                // A BLANK orderNumber, which is what InventoryUpdatedListener.requireUsable
                // actually rejects. This matters more than it looks: the first version of this
                // used a well-formed message naming an order that does not exist, and the service
                // handled it perfectly - settling a missing order is idempotent by design, so
                // nothing threw, nothing retried, and nothing was dead-lettered. The scenario
                // reported success while proving nothing.
                //
                // The lesson generalises: a poison message has to violate the consumer's actual
                // contract, not merely look wrong. Read requireUsable before writing one.
                //
                // Valid JSON that deserialises cleanly, so the listener IS entered and the retry
                // policy applies. Malformed bytes would fail in the deserializer instead, skipping
                // the retries entirely and landing straight in the error handler - a different path
                // from the one a real poison message takes.
                String payload = """
                        {"eventId":"%s","causationEventId":null,"orderNumber":"",\
                        "outcome":"","shortages":[],"occurredAt":"2026-01-01T00:00:00Z"}"""
                        .formatted(key);
                kafkaTemplate.send(inventoryUpdatedTopic, key, payload);
                log.warn("Chaos poison message published: key={} topic={}", key, inventoryUpdatedTopic);
                return key;
            }

            @Override
            public String publishOversizedMessage() {
                // The producer's max.request.size defaults to 1 MB; 4 MB is unambiguously over it
                // and is rejected inside the client, before a byte reaches the broker.
                String payload = "x".repeat(4 * 1024 * 1024);
                try {
                    kafkaTemplate.send(inventoryUpdatedTopic, "chaos-oversized", payload)
                            .get(10, TimeUnit.SECONDS);
                    return "unexpected: the broker accepted a 4MB record. "
                            + "Check max.request.size and message.max.bytes";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    log.warn("Chaos oversized send rejected as intended: {}", cause.toString());
                    return cause.getClass().getSimpleName() + ": " + cause.getMessage();
                }
            }

            @Override
            public String poisonTopic() {
                return inventoryUpdatedTopic;
            }

            @Override
            public String deadLetterTopic() {
                return deadLetterTopic;
            }
        };
    }

    /**
     * Drives real calls through the gRPC client so the breaker's configuration can be observed
     * reacting, rather than asserted about.
     *
     * <p>Uses {@link AvailabilityService}, which is the same path {@code POST
     * /api/v1/orders/availability} takes — so what is measured is the production code path with its
     * real deadlines, retry policy and fallbacks, not a test double that happens to share a breaker.
     */
    @Bean
    public ResilienceChaos orderResilienceChaos(
            CircuitBreaker inventoryCircuitBreaker, AvailabilityService availabilityService) {

        return new ResilienceChaos() {
            @Override
            public Map<String, Object> circuitBreakerState() {
                return snapshot(inventoryCircuitBreaker);
            }

            @Override
            public Map<String, Object> driveCalls(int calls) {
                Map<String, Object> before = snapshot(inventoryCircuitBreaker);
                int succeeded = 0;
                int failed = 0;
                for (int i = 0; i < calls; i++) {
                    try {
                        availabilityService.check(List.of(
                                new CreateOrderCommand.Line("CHAOS-PROBE-SKU", 1, BigDecimal.ONE)));
                        succeeded++;
                    } catch (RuntimeException e) {
                        // Counted, not propagated: the failures are the experiment. Aborting on the
                        // first one would stop exactly when the breaker is about to become
                        // interesting.
                        failed++;
                    }
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("outcome", "completed");
                result.put("calls", calls);
                result.put("succeeded", succeeded);
                result.put("failed", failed);
                result.put("stateBefore", before);
                result.put("stateAfter", snapshot(inventoryCircuitBreaker));
                result.put("note", "the breaker only trips if the dependency is genuinely failing. "
                        + "Break it first: ./scripts/chaos.sh down inventory-grpc");
                // Without this, the numbers read as a contradiction: 40 succeeded, 0 failed, and a
                // breaker that went OPEN at an 85% failure rate. Both are true and they are counting
                // different things - AvailabilityService degrades rather than throwing, so the CALLER
                // sees success while the CHANNEL sees failure. That gap is the fallback working, and
                // it is the single most important thing this endpoint has to say.
                result.put("readingTheNumbers", "succeeded/failed count what the CALLER saw; the "
                        + "breaker counts what the gRPC CHANNEL saw. High success with a tripped "
                        + "breaker is the fallback absorbing the outage, which is the design working.");
                return result;
            }
        };
    }

    private static Map<String, Object> snapshot(CircuitBreaker breaker) {
        CircuitBreaker.Metrics metrics = breaker.getMetrics();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("name", breaker.getName());
        state.put("state", breaker.getState().name());
        state.put("failureRatePercent", metrics.getFailureRate());
        state.put("slowCallRatePercent", metrics.getSlowCallRate());
        state.put("bufferedCalls", metrics.getNumberOfBufferedCalls());
        state.put("failedCalls", metrics.getNumberOfFailedCalls());
        state.put("slowCalls", metrics.getNumberOfSlowCalls());
        state.put("notPermittedCalls", metrics.getNumberOfNotPermittedCalls());
        return state;
    }
}
