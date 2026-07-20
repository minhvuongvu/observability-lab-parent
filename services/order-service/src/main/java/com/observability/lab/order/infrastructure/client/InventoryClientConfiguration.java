package com.observability.lab.order.infrastructure.client;

import com.observability.lab.shared.correlation.CorrelationContext;
import com.observability.lab.shared.correlation.CorrelationHeaders;
import com.observability.lab.shared.exception.IntegrationException;
import com.observability.lab.shared.exception.ResourceNotFoundException;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/**
 * Per-client Feign configuration for {@link InventoryClient}.
 *
 * <p>Deliberately <strong>not</strong> annotated {@code @Configuration}. Feign registers this class
 * into a child context of its own; annotating it would also let component scanning pick it up, and
 * these beans would then apply to every Feign client in the service rather than to this one.
 *
 * <p>Timeouts live in {@code application.yml} under
 * {@code spring.cloud.openfeign.client.config.inventory-service}, so they can be tuned per
 * environment without a rebuild.
 */
public class InventoryClientConfiguration {

    private static final String DEPENDENCY = "inventory-service";

    /**
     * Translates HTTP failures into the platform exception model.
     *
     * <p>Without this, Feign throws {@code FeignException}, the global handler cannot recognise it,
     * and a dependency being unavailable is reported to the caller as an internal error of this
     * service — misleading during an incident, and it points the investigation at the wrong system.
     */
    @Bean
    public ErrorDecoder inventoryErrorDecoder() {
        return (methodKey, response) -> {
            int status = response.status();

            // A 404 is a real answer, not a failure: the SKU does not exist. It maps to a
            // not-found for our caller rather than to a dependency fault, so the caller is not
            // told that inventory is broken when it is merely being accurate.
            if (status == 404) {
                return new ResourceNotFoundException("The requested product is not known to inventory.");
            }
            return IntegrationException.failed(
                    DEPENDENCY, "HTTP " + status + " from " + methodKey, null);
        };
    }

    /**
     * Carries the request's identity onto the outbound call.
     *
     * <p>Without it the correlation chain breaks at the process boundary and the downstream service
     * logs the same work under a different identifier, which is precisely where a distributed trace
     * is most needed.
     *
     * <p>The W3C {@code traceparent} header is not set here: constructing one requires minting a
     * child span id, which is the tracing SDK's job and arrives with the tracing step.
     */
    @Bean
    public RequestInterceptor correlationForwardingInterceptor() {
        return template -> {
            header(template, CorrelationHeaders.REQUEST_ID, CorrelationContext.requestId());
            header(template, CorrelationHeaders.CORRELATION_ID, CorrelationContext.correlationId());
            CorrelationContext.userId()
                    .ifPresent(userId -> template.header(CorrelationHeaders.USER_ID, userId));
        };
    }

    /**
     * No retries at the Feign layer.
     *
     * <p>Feign's default retryer would repeat a request that may not be idempotent, and it retries
     * inside the caller's timeout budget rather than outside it. Retry policy belongs with the
     * circuit breaker, one level up, where it can be applied per operation.
     */
    @Bean
    public Retryer inventoryRetryer() {
        return Retryer.NEVER_RETRY;
    }

    private static void header(RequestTemplate template, String name, String value) {
        if (value != null && !value.isBlank()) {
            template.header(name, value);
        }
    }
}
