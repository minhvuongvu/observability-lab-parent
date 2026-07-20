package com.observability.lab.shared.autoconfigure;

import com.observability.lab.shared.exception.ConstraintViolationAdvice;
import com.observability.lab.shared.exception.GlobalExceptionHandler;
import jakarta.validation.ConstraintViolationException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Registers the platform exception handlers on any servlet-based service.
 *
 * <p>The two advices are registered under separate conditions on purpose. The main handler needs
 * only Spring MVC and must always be present, because without it a service falls back to the
 * framework's whitelabel error body — losing the error code, the trace id and the envelope every
 * client parses. Only the Bean Validation advice depends on {@code jakarta.validation}, which is an
 * optional dependency of this library.
 *
 * <p>Gating both on validation, as an earlier version did, meant a service that declared only
 * {@code spring-boot-starter-web} silently got neither. Nothing failed, nothing logged; the error
 * contract was simply absent.
 *
 * <p>Both back off if the service defines its own bean of the same type.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RestControllerAdvice.class)
public class ExceptionHandlingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /**
     * Contributed only when Bean Validation is on the consuming service's classpath.
     *
     * <p>Nested so the outer configuration still loads without {@code jakarta.validation}: a
     * {@code @ConditionalOnClass} on the enclosing class would take the main handler down with it.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ConstraintViolationException.class)
    static class BeanValidationAdviceConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ConstraintViolationAdvice constraintViolationAdvice() {
            return new ConstraintViolationAdvice();
        }
    }
}
