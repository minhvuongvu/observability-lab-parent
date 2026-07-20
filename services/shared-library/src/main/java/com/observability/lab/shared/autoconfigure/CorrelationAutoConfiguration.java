package com.observability.lab.shared.autoconfigure;

import com.observability.lab.shared.correlation.CorrelationFilter;
import com.observability.lab.shared.correlation.ServiceIdentity;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Registers the correlation filter on any servlet-based service that depends on this library.
 *
 * <p>Auto-configuration rather than documentation. A cross-cutting concern that each service has to
 * remember to wire up is a concern that is missing from whichever service was written in a hurry,
 * and the gap only shows up during an incident, when its logs turn out to have no trace id.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(OncePerRequestFilter.class)
@EnableConfigurationProperties(ServiceIdentity.class)
public class CorrelationAutoConfiguration {

    /**
     * Registers the filter through a {@link FilterRegistrationBean} rather than exposing the filter
     * itself as a bean.
     *
     * <p>Spring Boot auto-registers any bean of type {@code Filter}, so publishing both would put
     * the filter into the chain twice — harmless-looking, and it would quietly replace the request
     * id halfway through every request.
     */
    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<CorrelationFilter> correlationFilterRegistration(ServiceIdentity identity) {
        FilterRegistrationBean<CorrelationFilter> registration =
                new FilterRegistrationBean<>(new CorrelationFilter(identity));
        registration.addUrlPatterns("/*");
        // Near the front of the chain, but leaving room ahead of it for anything that must run
        // first, such as a future request-decorating or tracing filter.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("correlationFilter");
        return registration;
    }
}
