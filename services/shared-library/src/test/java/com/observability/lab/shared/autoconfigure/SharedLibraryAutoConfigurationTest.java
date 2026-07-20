package com.observability.lab.shared.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.observability.lab.shared.correlation.CorrelationFilter;
import com.observability.lab.shared.correlation.ServiceIdentity;
import com.observability.lab.shared.exception.ConstraintViolationAdvice;
import com.observability.lab.shared.exception.GlobalExceptionHandler;
import com.observability.lab.shared.persistence.CorrelationAuditorAware;
import jakarta.validation.ConstraintViolationException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;

/**
 * Verifies the library wires itself into a consuming service.
 *
 * <p>Without this the unit tests could all pass while the filter and the handler were never
 * registered anywhere — every class correct, and the cross-cutting behaviour absent from every
 * service that depends on the module.
 */
@DisplayName("Shared library auto-configuration")
class SharedLibraryAutoConfigurationTest {

    private static final AutoConfigurations ALL = AutoConfigurations.of(
            CorrelationAutoConfiguration.class,
            ExceptionHandlingAutoConfiguration.class,
            PersistenceAutoConfiguration.class);

    private final WebApplicationContextRunner webRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(ALL)
                    .withPropertyValues(
                            "app.name=order-service", "app.version=1.2.3", "app.environment=test");

    private final ApplicationContextRunner plainRunner =
            new ApplicationContextRunner().withConfiguration(ALL);

    @Nested
    @DisplayName("in a servlet service")
    class ServletService {

        @Test
        @DisplayName("registers the correlation filter exactly once")
        void registersCorrelationFilter() {
            webRunner.run(context -> {
                assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                // Registered through a FilterRegistrationBean and NOT also as a bare Filter bean:
                // Spring Boot auto-registers any Filter bean, so both would put it in the chain
                // twice and the second pass would overwrite the first one's request id.
                assertThat(context).doesNotHaveBean(CorrelationFilter.class);
            });
        }

        @Test
        @DisplayName("registers the global exception handler and the validation advice")
        void registersExceptionHandler() {
            webRunner.run(context -> {
                assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                assertThat(context).hasSingleBean(ConstraintViolationAdvice.class);
            });
        }

        @Test
        @DisplayName("still registers the main handler when Bean Validation is absent")
        void handlerSurvivesWithoutBeanValidation() {
            // Regression test. Gating the whole configuration on jakarta.validation meant a service
            // declaring only spring-boot-starter-web got no handler at all, and silently fell back
            // to the framework's whitelabel error body: no error code, no trace id, no envelope.
            // Nothing failed and nothing logged, which is what made it easy to miss.
            webRunner.withClassLoader(new FilteredClassLoader(ConstraintViolationException.class))
                    .run(context -> {
                        assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                        assertThat(context).doesNotHaveBean(ConstraintViolationAdvice.class);
                    });
        }

        @Test
        @DisplayName("binds the service identity from app.* properties")
        void bindsServiceIdentity() {
            webRunner.run(context -> assertThat(context.getBean(ServiceIdentity.class))
                    .isEqualTo(new ServiceIdentity("order-service", "1.2.3", "test")));
        }

        @Test
        @DisplayName("falls back to placeholders when the identity is not configured")
        void identityFallsBack() {
            new WebApplicationContextRunner()
                    .withConfiguration(ALL)
                    .run(context -> assertThat(context.getBean(ServiceIdentity.class))
                            .isEqualTo(new ServiceIdentity("unknown-service", "unknown", "unknown")));
        }
    }

    @Nested
    @DisplayName("outside a servlet service")
    class NonServletService {

        @Test
        @DisplayName("contributes no web beans")
        void noWebBeans() {
            plainRunner.run(context -> {
                assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class);
            });
        }

        @Test
        @DisplayName("still contributes the auditor, which has nothing to do with HTTP")
        void stillContributesAuditor() {
            plainRunner.run(context -> assertThat(context).hasSingleBean(AuditorAware.class));
        }
    }

    @Nested
    @DisplayName("backing off")
    class BackingOff {

        @Test
        @DisplayName("lets a service replace the exception handler with its own")
        void serviceHandlerWins() {
            webRunner.withUserConfiguration(CustomHandlerConfig.class)
                    .run(context -> assertThat(context.getBean(GlobalExceptionHandler.class))
                            .isInstanceOf(CustomHandler.class));
        }

        @Test
        @DisplayName("lets a service replace the auditor with its own")
        void serviceAuditorWins() {
            webRunner.withUserConfiguration(CustomAuditorConfig.class).run(context -> {
                assertThat(context).hasSingleBean(AuditorAware.class);
                assertThat(context.getBean(AuditorAware.class))
                        .isNotInstanceOf(CorrelationAuditorAware.class);
            });
        }
    }

    static class CustomHandler extends GlobalExceptionHandler {}

    @Configuration(proxyBeanMethods = false)
    static class CustomHandlerConfig {
        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new CustomHandler();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAuditorConfig {
        @Bean
        AuditorAware<String> auditorAware() {
            return () -> Optional.of("custom");
        }
    }
}
