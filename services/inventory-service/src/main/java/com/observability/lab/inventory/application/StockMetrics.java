package com.observability.lab.inventory.application;

import com.observability.lab.shared.metrics.MetricTags;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Business metrics for stock reservation.
 *
 * <p>The signals here are about promises rather than processes. A reservation being rejected is not a
 * technical failure and never appears in an error rate — but a rejection rate that suddenly climbs is
 * the earliest sign that the catalogue and the warehouse disagree, and it is invisible in JVM or HTTP
 * metrics.
 *
 * <p>Nothing here is tagged by SKU. A product identifier is unbounded, and one time series per
 * product is how a metrics backend is brought down; "which SKU ran out" is a log question, and the
 * logs carry it.
 */
@Component
public class StockMetrics {

    private final MeterRegistry registry;
    private final DistributionSummary shortagesPerRejection;

    public StockMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.shortagesPerRejection = DistributionSummary
                .builder(MetricTags.NAMESPACE + "reservation.shortages")
                .description("How many lines were short on a rejected reservation")
                .baseUnit("lines")
                .register(registry);
    }

    /** Times one reservation attempt. */
    public Timer.Sample start() {
        return Timer.start(registry);
    }

    /**
     * Records the outcome of a reservation.
     *
     * <p>The timer is registered here, tagged, rather than up front untagged. Prometheus requires
     * every meter sharing a name to share its tag keys, so an untagged {@code lab.reservation.duration}
     * registered in the constructor would make this tagged one impossible to register — it is
     * rejected with a warning and produces no data at all, while the untagged one sits on the
     * dashboard reading zero forever.
     *
     * <p>Tagged by outcome, which has exactly three values. The ratio between {@code reserved} and
     * {@code rejected} is the business signal; {@code redelivery} is separated out so ordinary
     * at-least-once duplicates do not inflate either of the other two and make the rejection rate
     * look better or worse than it is.
     */
    public void recordOutcome(Timer.Sample sample, ReservationResult result) {
        String outcome = switch (result.outcome()) {
            case RESERVED -> "reserved";
            case REJECTED -> "rejected";
            case ALREADY_PROCESSED -> "redelivery";
        };

        sample.stop(Timer.builder(MetricTags.NAMESPACE + "reservation.duration")
                .description("Time to decide and apply a stock reservation, including the Oracle write")
                .tags(Tags.of(MetricTags.OUTCOME, outcome))
                .publishPercentileHistogram()
                .register(registry));

        Counter.builder(MetricTags.NAMESPACE + "reservations")
                .description("Stock reservation attempts by outcome")
                .tags(Tags.of(MetricTags.OUTCOME, outcome))
                .register(registry)
                .increment();

        if (result.outcome() == ReservationResult.Outcome.REJECTED) {
            shortagesPerRejection.record(result.shortages().size());
        }
    }

    /** A record was dead-lettered after exhausting its retries. */
    public void deadLettered(String reason) {
        Counter.builder(MetricTags.NAMESPACE + "messages.dead_lettered")
                .description("Records routed to the dead-letter topic")
                .tags(Tags.of("reason", reason))
                .register(registry)
                .increment();
    }
}
