package com.observability.lab.order.config;

import com.observability.lab.shared.correlation.ServiceIdentity;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the service in its generated OpenAPI document.
 *
 * <p>The version is taken from {@link ServiceIdentity}, which is filtered in from the Maven
 * coordinates at build time. A hand-maintained version string in a document that clients generate
 * code from goes stale silently, and the mismatch is only noticed when the generated client no
 * longer matches the running service.
 *
 * <p>The document is disabled entirely under the {@code prod} profile: a schema is an accurate map
 * of the attack surface.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    public OpenAPI orderServiceOpenApi(ServiceIdentity identity) {
        return new OpenAPI().info(new Info()
                .title("Order Service API")
                .version(identity.version())
                .description("""
                        Owns the order lifecycle for the Enterprise Microservice Observability Lab.

                        Every response, success or failure, is wrapped in the platform envelope and \
                        carries the request, correlation and trace identifiers in its `meta` block. \
                        Quote the trace id when reporting a problem.

                        Errors use codes prefixed `ORD-` for this context and `PLT-` for \
                        platform-level failures.""")
                .contact(new Contact().name("Observability Lab")));
    }
}
