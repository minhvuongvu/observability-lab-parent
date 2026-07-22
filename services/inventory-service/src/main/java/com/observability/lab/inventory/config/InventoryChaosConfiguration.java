package com.observability.lab.inventory.config;

import com.observability.lab.shared.chaos.DatabaseChaos;
import com.observability.lab.shared.chaos.MessagingChaos;
import java.time.Duration;
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
 * The Inventory Service's half of the chaos surface (step 17).
 *
 * <p>Same shape as the Order Service's, with two differences that are the whole reason this is a
 * per-service bean rather than something in the shared library: the sleep statement is Oracle's, not
 * PostgreSQL's, and there is no circuit breaker here to trip because this service is the gRPC
 * provider rather than a consumer of one.
 */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "dev"})
@ConditionalOnProperty(prefix = "app.chaos", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InventoryChaosConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InventoryChaosConfiguration.class);

    /**
     * Oracle's sleep, holding a real pool connection.
     *
     * <p>{@code DBMS_SESSION.SLEEP} rather than {@code DBMS_LOCK.SLEEP}: the latter needs an explicit
     * grant on {@code DBMS_LOCK} that the application user does not have, and the failure mode is an
     * {@code ORA-00904} that reads like a typo rather than a permission problem. {@code DBMS_SESSION}
     * is executable by {@code PUBLIC} from 18c onward, which covers the 23ai image this lab runs.
     *
     * <p>An anonymous PL/SQL block rather than a {@code SELECT}, because Oracle has no {@code
     * pg_sleep} equivalent that can be selected from {@code dual} without a function wrapper.
     */
    @Bean
    public DatabaseChaos inventoryDatabaseChaos(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new DatabaseChaos() {
            @Override
            public void sleepInDatabase(Duration duration) {
                jdbc.execute("BEGIN DBMS_SESSION.SLEEP(" + duration.toMillis() / 1000.0d + "); END;");
            }

            @Override
            public String databaseName() {
                return "Oracle (inventory schema)";
            }
        };
    }

    /**
     * Kafka faults on the topic this service consumes.
     *
     * <p>The poison message goes to {@code order-created}, which is this service's own inbound topic,
     * so the retries, the backoff and the dead-letter recoverer being exercised are the ones
     * configured here rather than in the other service.
     */
    @Bean
    public MessagingChaos inventoryMessagingChaos(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${app.kafka.topics.order-created}") String orderCreatedTopic,
            @Value("${app.kafka.topics.dead-letter}") String deadLetterTopic) {

        return new MessagingChaos() {
            @Override
            public String publishPoisonMessage() {
                String key = "chaos-poison-" + UUID.randomUUID();
                // An EMPTY items list, which is what OrderCreatedListener.requireUsable rejects.
                // Deliberately not "a negative quantity for a SKU that does not exist": that
                // deserialises, passes validation, and is handled as an ordinary rejected
                // reservation - so nothing retries and nothing is dead-lettered.
                //
                // A poison message must violate the consumer's actual contract rather than merely
                // look implausible. requireUsable is where that contract is written down.
                //
                // It still deserialises cleanly, so the listener is entered and the retry policy
                // applies - which is the path a real poison message takes, and the one a malformed
                // byte sequence would skip by failing in the deserializer instead.
                Map<String, Object> payload = Map.of(
                        "eventId", key,
                        "orderNumber", "",
                        "occurredAt", "2026-01-01T00:00:00Z",
                        "items", java.util.List.of());
                kafkaTemplate.send(orderCreatedTopic, key, payload);
                log.warn("Chaos poison message published: key={} topic={}", key, orderCreatedTopic);
                return key;
            }

            @Override
            public String publishOversizedMessage() {
                String payload = "x".repeat(4 * 1024 * 1024);
                try {
                    kafkaTemplate.send(orderCreatedTopic, "chaos-oversized", payload)
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
                return orderCreatedTopic;
            }

            @Override
            public String deadLetterTopic() {
                return deadLetterTopic;
            }
        };
    }
}
