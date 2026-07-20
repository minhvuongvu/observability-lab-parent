package com.observability.lab.shared.autoconfigure;

import com.observability.lab.shared.persistence.CorrelationAuditorAware;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;

/**
 * Supplies the auditor used to populate {@code created_by} and {@code updated_by}.
 *
 * <p>Only the {@link AuditorAware} bean is contributed. Auditing itself stays off until a service
 * declares {@code @EnableJpaAuditing}, because switching it on from a library would attach an entity
 * listener to every entity in every consuming service — including services that never asked for
 * auditing and have no columns for it.
 */
@AutoConfiguration
@ConditionalOnClass(AuditorAware.class)
public class PersistenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditorAware.class)
    public AuditorAware<String> correlationAuditorAware() {
        return new CorrelationAuditorAware();
    }
}
