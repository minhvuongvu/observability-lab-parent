package com.observability.lab.shared.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The set of faults currently switched on, and the single place anything asks "should this operation
 * misbehave right now".
 *
 * <p>Reads happen on every request, so the map is concurrent and the hot path is a lookup plus a
 * comparison. Expiry is lazy — checked on read rather than swept by a timer — because a fault that
 * has expired but not yet been noticed by anyone is, by definition, affecting nothing.
 *
 * <h2>Why this is metered</h2>
 *
 * <p>{@code lab.chaos.active} is published as a gauge, and it is the most important line in this
 * class. An injected fault and a real one produce identical symptoms in every other signal; without a
 * series saying "somebody did this on purpose", a dashboard screenshot from an experiment is
 * indistinguishable from a screenshot of an incident. Every panel in this lab can be overlaid with
 * this gauge to answer "was that us?".
 *
 * <p>It is also what makes the guard rails honest. A toggle left on shows up as a non-zero gauge on
 * every dashboard rather than as a mystery three days later.
 */
public class ChaosRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChaosRegistry.class);

    private final Map<String, ChaosToggle> toggles = new ConcurrentHashMap<>();
    private final ChaosProperties properties;
    private final AtomicInteger activeCount = new AtomicInteger();

    public ChaosRegistry(ChaosProperties properties) {
        this.properties = properties;
    }

    /**
     * The counter behind {@code lab.chaos.active}, bound to the meter registry by a
     * {@link io.micrometer.core.instrument.binder.MeterBinder} in ChaosAutoConfiguration.
     *
     * <p>Bound there rather than registered here, and the difference was a real bug. Taking a
     * {@code MeterRegistry} in this constructor resolved to null: auto-configurations are ordered,
     * and this one runs before Micrometer has contributed the registry's bean definition, so
     * {@code ObjectProvider.getIfAvailable()} truthfully reported "no registry" and the gauge was
     * silently never created. Nothing failed; the series simply did not exist, which is the worst
     * shape a metrics bug can take.
     *
     * <p>A MeterBinder is applied by Spring Boot once the registry is ready, which removes the
     * ordering question entirely.
     */
    AtomicInteger activeCounter() {
        return activeCount;
    }

    /**
     * Switches a fault on, replacing any toggle of the same name.
     *
     * <p>Replace rather than stack: two latency toggles would add up, and discovering that you ran the
     * same command twice by reading a latency graph is a poor use of an afternoon.
     */
    public ChaosToggle inject(String name, double ratio, Duration ttl, Map<String, Object> attributes) {
        Instant now = Instant.now();
        Duration resolved = properties.resolveTtl(ttl);
        ChaosToggle toggle = new ChaosToggle(name, ratio, attributes, now, now.plus(resolved));
        toggles.put(name, toggle);
        refreshCount();
        // WARN, not INFO. This line is the audit trail for "why was the service broken at 14:32",
        // and it has to survive a log level that has been turned down to quieten a noisy incident.
        log.warn("Chaos fault injected: name={} ratio={} ttl={}s attributes={}",
                name, toggle.ratio(), resolved.toSeconds(), toggle.attributes());
        return toggle;
    }

    /** The toggle for {@code name}, if one is active and unexpired. */
    public Optional<ChaosToggle> find(String name) {
        ChaosToggle toggle = toggles.get(name);
        if (toggle == null) {
            return Optional.empty();
        }
        if (toggle.isExpired(Instant.now())) {
            if (toggles.remove(name, toggle)) {
                refreshCount();
                log.warn("Chaos fault expired: name={}", name);
            }
            return Optional.empty();
        }
        return Optional.of(toggle);
    }

    /**
     * Whether this particular operation should be affected.
     *
     * <p>The dice are rolled per call rather than per toggle, which is what {@code ratio} means: at
     * 0.1 roughly one operation in ten fails, not the same tenth every time.
     */
    public boolean shouldApply(String name) {
        return find(name)
                .map(t -> t.ratio() >= 1.0d || ThreadLocalRandom.current().nextDouble() < t.ratio())
                .orElse(false);
    }

    /** Switches one fault off. Returns whether anything was actually on. */
    public boolean clear(String name) {
        boolean removed = toggles.remove(name) != null;
        if (removed) {
            refreshCount();
            log.warn("Chaos fault cleared: name={}", name);
        }
        return removed;
    }

    /** Switches everything off. The command that must always return the process to clean. */
    public int clearAll() {
        int cleared = toggles.size();
        toggles.clear();
        refreshCount();
        if (cleared > 0) {
            log.warn("Chaos faults cleared: count={}", cleared);
        }
        return cleared;
    }

    /** Every unexpired toggle, for {@code GET /api/v1/chaos}. */
    public List<ChaosToggle> active() {
        Instant now = Instant.now();
        toggles.values().removeIf(t -> t.isExpired(now));
        refreshCount();
        return List.copyOf(toggles.values());
    }

    private void refreshCount() {
        activeCount.set(toggles.size());
    }
}
