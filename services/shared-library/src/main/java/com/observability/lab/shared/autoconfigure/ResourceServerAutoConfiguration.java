package com.observability.lab.shared.autoconfigure;

import com.observability.lab.shared.security.KeycloakRealmRoleConverter;
import com.observability.lab.shared.security.SecurityUserMdcFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Turns every servlet service that depends on this library into an OAuth2 resource server, with one
 * identical policy, so "protect the API" is not a thing each service reimplements slightly
 * differently.
 *
 * <p>The gateway already verifies the token at the edge (step 06/07). Verifying it again here is
 * deliberate defence in depth: the edge decides only <em>whether</em> a caller is authenticated,
 * while authorization is per endpoint and per role, which only the service knows. A service that
 * trusted the edge blindly would also be wide open the moment someone reached its port directly —
 * and in this lab the service ports are reachable on the host.
 *
 * <p>Policy:
 *
 * <ul>
 *   <li>{@code /actuator/**} and the OpenAPI/Swagger endpoints are open. Health and readiness feed
 *       the gateway and container probes, which present no token; the API docs describe a surface
 *       that is itself protected.
 *   <li>{@code /api/v1/chaos/**} requires {@code ADMIN} on every method. Those endpoints exist only
 *       under {@code local} and {@code dev}, and can break the process on purpose.
 *   <li>{@code DELETE /api/**} requires {@code ADMIN}. Destructive operations are the one place a
 *       plain user and an operator must differ.
 *   <li>every other {@code /api/**} call requires {@code USER} or {@code ADMIN}.
 *   <li>anything else must merely be authenticated.
 * </ul>
 *
 * <p>Ordered before Spring Boot's own security auto-configuration so this chain, not Boot's
 * authenticate-everything default, is the one that takes effect.
 */
@AutoConfiguration
@AutoConfigureBefore(
        name = {
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet."
                    + "OAuth2ResourceServerAutoConfiguration"
        })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({SecurityFilterChain.class, JwtDecoder.class, BearerTokenAuthenticationFilter.class})
public class ResourceServerAutoConfiguration {

    /**
     * Decodes and validates bearer tokens against the realm's published keys.
     *
     * <p>Built from the JWKS endpoint rather than the issuer URI on purpose: {@code withJwkSetUri}
     * fetches keys lazily on the first token, so the service starts even when Keycloak is not up
     * yet, whereas issuer-based construction performs OIDC discovery eagerly at startup and couples
     * the service's boot to the identity provider's. The issuer is still verified on every token —
     * {@link JwtValidators#createDefaultWithIssuer} adds issuer, expiry and not-before checks — so
     * laziness costs no security.
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${app.security.issuer-uri}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    /**
     * Translates a validated token into an {@link org.springframework.security.core.Authentication}:
     * realm roles become authorities, and the principal name is the human {@code preferred_username}
     * rather than the opaque subject UUID, so both {@code SecurityContext} and the logs read as the
     * actual user.
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                // No browser session and no server-side state: every request re-presents its token,
                // which is exactly what makes CSRF inapplicable and the session policy stateless.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/error").permitAll()
                        // Chaos endpoints (step 17). ADMIN on every method, not just DELETE:
                        // a POST here can deadlock the process, so "destructive" is the wrong
                        // axis to reason about and the verb tells you nothing.
                        //
                        // This rule is stated unconditionally, even though the endpoints only
                        // exist under local and dev. A matcher for a path that is not mapped
                        // costs nothing, and the alternative - a profile-conditional security
                        // rule - is how an endpoint ends up briefly unguarded when somebody
                        // changes which profiles register it.
                        .requestMatchers("/api/v1/chaos/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasAnyRole("USER", "ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                // Runs once the token is validated, so the authenticated subject is on every log
                // line the request produces from here on.
                .addFilterAfter(new SecurityUserMdcFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
