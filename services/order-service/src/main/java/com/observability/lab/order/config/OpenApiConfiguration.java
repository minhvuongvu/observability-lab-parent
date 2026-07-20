package com.observability.lab.order.config;

import com.observability.lab.shared.correlation.ServiceIdentity;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
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
 *
 * <p>Declares a single bearer-JWT security scheme so Swagger UI shows an Authorize button. The API
 * is protected (step 07); without this, every "Try it out" would return 401 and the document would
 * misrepresent how the service is actually called.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    private static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI orderServiceOpenApi(ServiceIdentity identity) {
        return new OpenAPI()
                .info(new Info()
                        .title("Order Service API")
                        .version(identity.version())
                        .description("""
                                Owns the order lifecycle for the Enterprise Microservice Observability Lab.

                                Every response, success or failure, is wrapped in the platform envelope and \
                                carries the request, correlation and trace identifiers in its `meta` block. \
                                Quote the trace id when reporting a problem.

                                Errors use codes prefixed `ORD-` for this context and `PLT-` for \
                                platform-level failures.""")
                        .contact(new Contact().name("Observability Lab")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Keycloak access token. Obtain one with ./scripts/token.sh.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
