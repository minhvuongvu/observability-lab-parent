package com.observability.lab.shared.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Verifies that the gRPC interceptors actually reach a service that speaks the protocol.
 *
 * <p>The ordering here is the real subject. {@code @ConditionalOnBean} on an auto-configuration is
 * evaluated in auto-configuration order, so a chain that depends on a {@link MeterRegistry} can be
 * skipped entirely if it is considered before the registry has been contributed. The failure is
 * silent — no interceptors, no gRPC metrics, and the server refuses to start for want of a bean
 * nobody noticed was missing — which is exactly the sort of thing a unit test of each interceptor
 * would never catch.
 *
 * <p>So Spring Boot's own metrics auto-configurations are included, and the runner sorts them the
 * way a real application would.
 */
@DisplayName("gRPC auto-configuration")
class GrpcAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    // Boot's, in the order a real service gets them.
                    org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration.class,
                    CompositeMeterRegistryAutoConfiguration.class,
                    SimpleMetricsExportAutoConfiguration.class,
                    MetricsAutoConfiguration.class,
                    GrpcAutoConfiguration.class))
            .withPropertyValues("app.name=inventory-service", "app.version=1.2.3",
                    "app.environment=test");

    @Nested
    @DisplayName("on a service that speaks gRPC")
    class GrpcService {

        @Test
        @DisplayName("contributes every interceptor and the assembled chain")
        void contributesTheChain() {
            runner.run(context -> {
                assertThat(context).hasSingleBean(GrpcCorrelationServerInterceptor.class);
                assertThat(context).hasSingleBean(GrpcMetricsServerInterceptor.class);
                assertThat(context).hasSingleBean(GrpcLoggingServerInterceptor.class);
                assertThat(context).hasSingleBean(GrpcExceptionServerInterceptor.class);
                assertThat(context).hasSingleBean(GrpcCorrelationClientInterceptor.class);
                assertThat(context).hasSingleBean(GrpcMetricsClientInterceptor.class);
                assertThat(context).hasSingleBean(GrpcServerInterceptorChain.class);
            });
        }

        @Test
        @DisplayName("orders the chain so each interceptor can do its job")
        void ordersTheChain() {
            runner.run(context -> {
                List<ServerInterceptor> chain =
                        context.getBean(GrpcServerInterceptorChain.class).outermostFirst();

                // Correlation outermost: a failure logged during authentication must already have a
                // trace id. Exception mapping innermost: outside the logging interceptor it would
                // record UNKNOWN for every failure, because gRPC's own fallback would run first.
                assertThat(chain.getFirst()).isInstanceOf(GrpcCorrelationServerInterceptor.class);
                assertThat(chain.getLast()).isInstanceOf(GrpcExceptionServerInterceptor.class);

                // Metrics before logging, so the timer spans the whole of handling.
                assertThat(indexOf(chain, GrpcMetricsServerInterceptor.class))
                        .isLessThan(indexOf(chain, GrpcLoggingServerInterceptor.class));
            });
        }

        @Test
        @DisplayName("adds token verification when the service is a resource server")
        void verifiesTokensWhenThereIsADecoder() {
            runner.withUserConfiguration(DecoderConfig.class).run(context -> {
                assertThat(context).hasSingleBean(GrpcAuthenticationServerInterceptor.class);

                List<ServerInterceptor> chain =
                        context.getBean(GrpcServerInterceptorChain.class).outermostFirst();

                // Inside logging and metrics, so a rejected call still appears on the dashboards.
                // An UNAUTHENTICATED invisible to both is how a misconfigured client goes unnoticed.
                assertThat(indexOf(chain, GrpcAuthenticationServerInterceptor.class))
                        .isGreaterThan(indexOf(chain, GrpcLoggingServerInterceptor.class))
                        .isLessThan(indexOf(chain, GrpcExceptionServerInterceptor.class));
            });
        }

        @Test
        @DisplayName("leaves the chain without authentication rather than half-configured")
        void noDecoderMeansNoAuthenticationInterceptor() {
            // A service that serves gRPC and is not a resource server gets no authentication
            // interceptor, rather than one that cannot verify anything.
            runner.run(context ->
                    assertThat(context).doesNotHaveBean(GrpcAuthenticationServerInterceptor.class));
        }

        @Test
        @DisplayName("can be switched off for local experimentation, and defaults to on")
        void authenticationIsOptOut() {
            runner.withUserConfiguration(DecoderConfig.class)
                    .withPropertyValues("app.grpc.server.authentication.enabled=false")
                    .run(context -> assertThat(context)
                            .doesNotHaveBean(GrpcAuthenticationServerInterceptor.class));
        }
    }

    @Nested
    @DisplayName("on a service that does not")
    class NonGrpcService {

        @Test
        @DisplayName("contributes nothing when gRPC is not on the classpath")
        void backsOffEntirely() {
            // The library must not force a transport onto a service that never asked for one.
            runner.withClassLoader(new FilteredClassLoader(ServerInterceptor.class))
                    .run(context -> assertThat(context)
                            .doesNotHaveBean(GrpcServerInterceptorChain.class));
        }
    }

    private static int indexOf(List<ServerInterceptor> chain, Class<?> type) {
        for (int i = 0; i < chain.size(); i++) {
            if (type.isInstance(chain.get(i))) {
                return i;
            }
        }
        throw new AssertionError(type.getSimpleName() + " is not in the chain");
    }

    @Configuration(proxyBeanMethods = false)
    static class DecoderConfig {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("not called in this test");
            };
        }
    }
}
