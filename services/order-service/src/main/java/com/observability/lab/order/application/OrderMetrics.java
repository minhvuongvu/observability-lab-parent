package com.observability.lab.order.application;

import com.observability.lab.order.domain.OrderStatus;
import com.observability.lab.shared.metrics.MetricTags;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Business metrics for the order lifecycle.
 *
 * <p>Everything Spring registers automatically answers "is the process healthy": heap, threads, pool
 * saturation, request latency. None of it answers "is the <em>business</em> healthy". A deployment
 * that stops accepting orders while every technical metric stays green is the failure mode this class
 * exists to make visible.
 *
 * <p>Registered in one place rather than scattered across the services that record them, so the
 * metric names — which are a contract with every dashboard and alert built on them — can be read at
 * once. Each of Micrometer's five instrument types is used here for the thing it is actually for:
 *
 * <ul>
 *   <li>{@link Counter} — a monotonic count of events. Rates are derived at query time.
 *   <li>{@link Timer} — how long something took, plus how often.
 *   <li>{@link DistributionSummary} — the distribution of a non-time quantity (order value).
 *   <li>{@link io.micrometer.core.instrument.Gauge} — a value sampled when scraped (outbox backlog).
 *   <li>{@link LongTaskTimer} — how long an <em>in-flight</em> task has been running.
 * </ul>
 */
@Component
public class OrderMetrics {

    private final MeterRegistry registry;

    /**
     * Outbox backlog, published as a gauge.
     *
     * <p>A gauge, not a counter: the question is "how many events are owed to Kafka right now", which
     * can go down as well as up. Held in an {@link AtomicLong} the relay updates, rather than as a
     * lambda that queries the database on every scrape — a gauge callback runs on the scrape thread,
     * and putting a SQL query there means the metrics endpoint fails whenever the database is slow,
     * which is exactly when it is most needed.
     */
    private final AtomicLong outboxBacklog = new AtomicLong();

    private final DistributionSummary orderValue;
    private final LongTaskTimer relayRun;

    public OrderMetrics(MeterRegistry registry) {
        this.registry = registry;

        io.micrometer.core.instrument.Gauge
                .builder(MetricTags.NAMESPACE + "outbox.pending", outboxBacklog, AtomicLong::get)
                .description("Events written to the outbox but not yet acknowledged by Kafka")
                .baseUnit("events")
                .register(registry);

        this.orderValue = DistributionSummary
                .builder(MetricTags.NAMESPACE + "order.value")
                .description("Monetary value of accepted orders")
                .baseUnit("currency")
                // Percentiles of order value answer "is the median basket shrinking", which an
                // average cannot: one large order moves a mean and leaves the median alone.
                .publishPercentileHistogram()
                .register(registry);

        this.relayRun = LongTaskTimer
                .builder(MetricTags.NAMESPACE + "outbox.relay.active")
                .description("Duration of the currently running outbox relay pass")
                .register(registry);
    }

    /**
     * An order was accepted.
     *
     * <p>Tagged by currency, which has a handful of values. Deliberately <em>not</em> tagged by
     * customer or SKU: those are unbounded, and one time series per customer is how a metrics backend
     * is brought down. Those questions belong in logs, which are indexed for them.
     */
    public void orderAccepted(String currency, BigDecimal total) {
        Counter.builder(MetricTags.NAMESPACE + "orders.accepted")
                .description("Orders accepted and persisted as PENDING")
                .tags(Tags.of(MetricTags.CURRENCY, currency))
                .register(registry)
                .increment();

        orderValue.record(total.doubleValue());
    }

    /**
     * An order reached a terminal-ish state after the Inventory Service answered.
     *
     * <p>The ratio of this to {@code orders.accepted} is the single most useful business signal the
     * platform has: a gap between them means orders are being accepted and never settled, which is
     * invisible in every technical metric.
     */
    public void orderSettled(OrderStatus status) {
        Counter.builder(MetricTags.NAMESPACE + "orders.settled")
                .description("Orders that reached CONFIRMED or REJECTED")
                .tags(Tags.of(MetricTags.OUTCOME, status.name().toLowerCase(java.util.Locale.ROOT)))
                .register(registry)
                .increment();
    }

    /** An order was withdrawn. */
    public void orderCancelled() {
        Counter.builder(MetricTags.NAMESPACE + "orders.cancelled")
                .description("Orders withdrawn by a customer or an operator")
                .register(registry)
                .increment();
    }

    /**
     * Times an invoice upload, including the failure path.
     *
     * <p>The timer is registered on {@link #recordInvoiceUpload} rather than up front, because it
     * carries an {@code outcome} tag. Pre-registering an untagged timer of the same name would make
     * the tagged one impossible to register at all: Prometheus requires every meter sharing a name
     * to share its tag keys, so the second registration is rejected and silently produces nothing.
     */
    public Timer.Sample startInvoiceUpload() {
        return Timer.start(registry);
    }

    public void recordInvoiceUpload(Timer.Sample sample, boolean succeeded) {
        sample.stop(Timer.builder(MetricTags.NAMESPACE + "invoice.upload")
                .description("Time to render and store an invoice in object storage")
                .tag(MetricTags.OUTCOME, succeeded ? "success" : "failed")
                .register(registry));
    }

    /** The relay's current backlog, refreshed each pass. */
    public void outboxBacklog(long pending) {
        outboxBacklog.set(pending);
    }

    /**
     * Wraps a relay pass in a long task timer.
     *
     * <p>A plain {@link Timer} only records a task once it has finished, so a relay pass wedged
     * against an unreachable broker shows up as nothing at all until it eventually completes. A long
     * task timer reports the duration of work still in flight, which is the only way that stall is
     * visible while it is happening.
     */
    public LongTaskTimer.Sample startRelayPass() {
        return relayRun.start();
    }
}
