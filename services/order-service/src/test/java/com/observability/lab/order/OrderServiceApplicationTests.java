package com.observability.lab.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Smoke test guarding the service bootstrap.
 *
 * <p>It proves the Spring context starts and that the identity the platform relies on for service
 * discovery, log fields and metric tags is resolved from configuration rather than defaulted.
 */
@SpringBootTest
class OrderServiceApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Spring context starts with the expected service identity")
    void contextLoadsWithServiceIdentity() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getEnvironment().getProperty("spring.application.name"))
                .isEqualTo("order-service");
        assertThat(applicationContext.getEnvironment().getProperty("app.environment"))
                .isEqualTo("local");
    }
}
