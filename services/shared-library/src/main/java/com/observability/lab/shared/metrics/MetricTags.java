package com.observability.lab.shared.metrics;

/**
 * Metric and tag names shared across the platform.
 *
 * <p>The same kind of contract {@link com.observability.lab.shared.correlation.CorrelationFields} is
 * for logs: these strings end up in PromQL queries, dashboard panels and alert rules, so renaming one
 * silently breaks everything built on it. They are treated as public API.
 *
 * <p>Tag names deliberately match the log field names — {@code service}, {@code environment},
 * {@code version} — so a dashboard panel and a log query filter on the identical vocabulary rather
 * than forcing a translation during an incident.
 */
public final class MetricTags {

    /**
     * Prefix for every metric this platform defines itself.
     *
     * <p>A namespace separates business metrics from the hundreds a framework registers. It also
     * makes {@code lab_*} a single scrape-config-level answer to "what did we write, as opposed to
     * what did Spring give us".
     */
    public static final String NAMESPACE = "lab.";

    // --- Common tags, applied to every meter -------------------------------

    public static final String SERVICE = "service";
    public static final String ENVIRONMENT = "environment";
    public static final String VERSION = "version";

    // --- Business dimensions -----------------------------------------------

    /** Outcome of an operation: {@code success}, {@code rejected}, {@code failed}. */
    public static final String OUTCOME = "outcome";

    /** ISO 4217 currency of an order. Low cardinality by nature. */
    public static final String CURRENCY = "currency";

    /**
     * Product identifier.
     *
     * <p>Present as a constant so that its <em>absence</em> from the meters is a deliberate,
     * documented choice rather than an oversight: a SKU is unbounded, and one time series per
     * product is how a metrics backend falls over. Per-SKU questions belong in logs, which are
     * indexed for exactly that.
     */
    public static final String PRODUCT_SKU = "product_sku";

    private MetricTags() {
        throw new AssertionError("No instances.");
    }
}
