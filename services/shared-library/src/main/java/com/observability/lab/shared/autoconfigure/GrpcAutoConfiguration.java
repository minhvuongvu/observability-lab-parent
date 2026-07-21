package com.observability.lab.shared.autoconfigure;

import com.observability.lab.shared.correlation.ServiceIdentity;
import com.observability.lab.shared.grpc.GrpcAuthenticationServerInterceptor;
import com.observability.lab.shared.grpc.GrpcCorrelationClientInterceptor;
import com.observability.lab.shared.grpc.GrpcCorrelationServerInterceptor;
import com.observability.lab.shared.grpc.GrpcExceptionServerInterceptor;
import com.observability.lab.shared.grpc.GrpcLoggingServerInterceptor;
import com.observability.lab.shared.grpc.GrpcMetricsClientInterceptor;
import com.observability.lab.shared.grpc.GrpcMetricsServerInterceptor;
import com.observability.lab.shared.grpc.GrpcServerInterceptorChain;
import io.grpc.ServerInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Contributes the platform's gRPC interceptors to any service that speaks the protocol.
 *
 * <p>Auto-configuration for the same reason the correlation filter is: correlation, log shape,
 * status mapping and metric names must be identical on the client and the server, and a
 * cross-cutting concern each service has to wire up by hand is one that is missing from whichever
 * service was written in a hurry. The gap only shows up during an incident, when one side of the hop
 * turns out to have no trace id.
 *
 * <p>What this does <strong>not</strong> do is start a server or build a channel. Those are
 * decisions about ports, discovery and deadlines that belong to the service making them — the
 * library supplies the behaviour, not the topology.
 */
@AutoConfiguration(
        after = MetricsAutoConfiguration.class,
        afterName = "org.springframework.boot.actuate.autoconfigure.metrics."
                + "CompositeMeterRegistryAutoConfiguration")
@ConditionalOnClass({ServerInterceptor.class, MeterRegistry.class})
@ConditionalOnBean(MeterRegistry.class)
@EnableConfigurationProperties(ServiceIdentity.class)
public class GrpcAutoConfiguration {

    /*
     * The ordering above is load-bearing, not tidiness.
     *
     * @ConditionalOnBean is evaluated in auto-configuration order, so without an explicit anchor
     * this whole class is considered before Spring Boot has contributed a MeterRegistry - the
     * condition fails, every interceptor silently disappears, and the gRPC server then refuses to
     * start for want of a chain bean nobody noticed was missing.
     *
     * CompositeMeterRegistryAutoConfiguration is the right anchor because Boot orders every
     * registry export auto-configuration before it, so being after the composite means being after
     * all of them - Prometheus, simple, and anything a service adds later.
     */

    @Bean
    @ConditionalOnMissingBean
    public GrpcCorrelationServerInterceptor grpcCorrelationServerInterceptor(ServiceIdentity identity) {
        return new GrpcCorrelationServerInterceptor(identity);
    }

    @Bean
    @ConditionalOnMissingBean
    public GrpcMetricsServerInterceptor grpcMetricsServerInterceptor(MeterRegistry registry) {
        return new GrpcMetricsServerInterceptor(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public GrpcLoggingServerInterceptor grpcLoggingServerInterceptor() {
        return new GrpcLoggingServerInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public GrpcExceptionServerInterceptor grpcExceptionServerInterceptor() {
        return new GrpcExceptionServerInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public GrpcCorrelationClientInterceptor grpcCorrelationClientInterceptor(ServiceIdentity identity) {
        return new GrpcCorrelationClientInterceptor(identity);
    }

    @Bean
    @ConditionalOnMissingBean
    public GrpcMetricsClientInterceptor grpcMetricsClientInterceptor(MeterRegistry registry) {
        return new GrpcMetricsClientInterceptor(registry);
    }

    /**
     * Token verification on the gRPC port.
     *
     * <p>Nested so it activates only where there is a {@link JwtDecoder} to verify with. A service
     * that serves gRPC and is not a resource server simply has no authentication interceptor, rather
     * than a broken one.
     *
     * <p>Can be turned off with {@code app.grpc.server.authentication.enabled=false} — which exists
     * for local experimentation with {@code grpcurl}, and is the reason it defaults to <em>on</em>
     * rather than to whatever the developer last set.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JwtDecoder.class)
    @ConditionalOnBean(JwtDecoder.class)
    @ConditionalOnProperty(prefix = "app.grpc.server.authentication", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class GrpcAuthenticationConfiguration {

        @Bean
        @ConditionalOnMissingBean
        GrpcAuthenticationServerInterceptor grpcAuthenticationServerInterceptor(JwtDecoder decoder) {
            return new GrpcAuthenticationServerInterceptor(decoder);
        }
    }

    /**
     * Assembles the chain in the one order that works.
     *
     * <p>Built explicitly rather than by injecting {@code List<ServerInterceptor>}: Spring's
     * collection ordering is by {@code @Order} and bean-definition order, neither of which conveys
     * that logging must observe the mapped status and that authentication must be inside the metrics
     * timer. Those constraints belong in code that can explain itself.
     */
    @Bean
    @ConditionalOnMissingBean
    public GrpcServerInterceptorChain grpcServerInterceptorChain(
            GrpcCorrelationServerInterceptor correlation,
            GrpcMetricsServerInterceptor metrics,
            GrpcLoggingServerInterceptor logging,
            GrpcExceptionServerInterceptor exceptions,
            java.util.Optional<GrpcAuthenticationServerInterceptor> authentication) {

        List<ServerInterceptor> chain = new ArrayList<>();
        chain.add(correlation);
        chain.add(metrics);
        chain.add(logging);
        authentication.ifPresent(chain::add);
        chain.add(exceptions);
        return new GrpcServerInterceptorChain(chain);
    }
}
