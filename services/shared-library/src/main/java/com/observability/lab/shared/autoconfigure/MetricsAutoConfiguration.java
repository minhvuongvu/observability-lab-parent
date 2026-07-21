package com.observability.lab.shared.autoconfigure;

import com.observability.lab.shared.correlation.ServiceIdentity;
import com.observability.lab.shared.metrics.MetricTags;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import java.time.Duration;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Gives every metric this platform exports the same identity and the same statistical shape.
 *
 * <p>Auto-configuration rather than documentation, for the same reason the correlation filter is:
 * a cross-cutting concern each service has to remember to wire up is one that is missing from
 * whichever service was written in a hurry — and an untagged metric is discovered when a dashboard
 * silently aggregates two services into one line.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@EnableConfigurationProperties(ServiceIdentity.class)
public class MetricsAutoConfiguration {

    /**
     * Stamps {@code service}, {@code environment} and {@code version} onto every meter.
     *
     * <p>These are the same three fields every log line carries, deliberately: a dashboard panel and
     * a log query should be filterable by the identical labels, or correlating them means translating
     * between two vocabularies during an incident.
     *
     * <p>Common tags rather than per-meter tags. Adding them at the registry means a meter registered
     * by a library — Hikari's, Kafka's, Tomcat's — carries them too, which no amount of discipline in
     * application code could achieve.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags(ServiceIdentity identity) {
        return registry -> registry.config().commonTags(
                MetricTags.SERVICE, identity.name(),
                MetricTags.ENVIRONMENT, identity.environment(),
                MetricTags.VERSION, identity.version());
    }

    /**
     * Publishes histograms for the latencies that get alerted on.
     *
     * <p>A timer publishes a count, a sum and a max by default. That is enough for an average, and an
     * average latency is the number that hides every problem worth finding: it cannot express "one
     * request in a hundred takes four seconds". A histogram can, because a percentile is computable
     * from it.
     *
     * <p>Crucially the percentile is computed <em>server-side, from bucket counts</em>, rather than
     * pre-computed in each process. Pre-computed percentiles cannot be aggregated — the 99th
     * percentile of three instances is not the average of their three 99th percentiles — so they
     * become meaningless the moment a service is scaled out. Buckets add up correctly.
     *
     * <p>The cost is real and is the reason this is not applied to every timer: each histogram is one
     * time series per bucket per tag combination, which is how a metrics bill becomes a surprise.
     */
    @Bean
    public MeterFilter latencyHistograms() {
        return new MeterFilter() {
            @Override
            public io.micrometer.core.instrument.distribution.DistributionStatisticConfig configure(
                    io.micrometer.core.instrument.Meter.Id id,
                    io.micrometer.core.instrument.distribution.DistributionStatisticConfig config) {

                if (!shouldHaveHistogram(id.getName())) {
                    return config;
                }
                return io.micrometer.core.instrument.distribution.DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        // Bounded on both ends: without a maximum, a single pathological request
                        // creates buckets all the way to the default 30-second ceiling and multiplies
                        // the series count for every tag combination.
                        .minimumExpectedValue(Duration.ofMillis(5).toNanos())
                        .maximumExpectedValue(Duration.ofSeconds(10).toNanos())
                        .expiry(Duration.ofMinutes(5))
                        .build()
                        .merge(config);
            }
        };
    }

    /**
     * Which timers earn a histogram.
     *
     * <p>Deliberately a short list, and every entry is a latency something actually waits on.
     * Everything else keeps the cheap count/sum/max form.
     *
     * <p>The three infrastructure timers are here because a <em>percentile</em> of each answers a
     * question a mean cannot. Mean connection-acquire time stays flat while one request in a hundred
     * waits two seconds for the pool — and that one request is the incident. The same argument
     * applies to Redis commands and to GC pauses, where the tail is the whole story.
     *
     * <p>The cost is bounded: two pools, one Redis client and a handful of GC causes, so this adds
     * tens of series rather than thousands. A histogram on a per-endpoint or per-SKU timer would be
     * a very different proposition.
     */
    private static boolean shouldHaveHistogram(String name) {
        return name.startsWith("http.server.requests")
                || name.startsWith("http.client.requests")
                // Both sides of the gRPC hop, and both are needed: the gap between them is network,
                // queueing and connection establishment. A client p99 of 400ms against a server p99
                // of 15ms is not a slow service, it is a saturated channel - and only having both
                // numbers distinguishes them.
                //
                // There is a second reason here that HTTP does not have. The deadline is a hard
                // boundary: an RPC either completes inside it or returns DEADLINE_EXCEEDED, so the
                // fraction of calls landing in the bucket just below the deadline is how a timeout
                // is seen coming before it starts firing.
                || name.startsWith("grpc.server.request.duration")
                || name.startsWith("grpc.client.request.duration")
                || name.startsWith(MetricTags.NAMESPACE)
                || name.startsWith("hikaricp.connections.acquire")
                || name.startsWith("hikaricp.connections.usage")
                || name.startsWith("lettuce.command.completion")
                || name.equals("jvm.gc.pause");
    }
}
