package com.observability.lab.order.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.observability.lab.order.application.OrderApplicationService;
import com.observability.lab.shared.autoconfigure.ResourceServerAutoConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Security slice.
 *
 * <p>Proves the resource-server policy the shared library installs actually guards this service's
 * API. Unlike {@link OrderControllerTest}, which runs with filters off to isolate controller
 * behaviour, this test runs with the security filter chain on and asserts only the status the policy
 * produces — the point is authentication and authorization, not the response body.
 *
 * <p>The two JWKS/issuer properties are what the shared {@code JwtDecoder} binds to at construction;
 * they are never reached because {@link org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors#jwt()
 * jwt()} supplies an already-authenticated token, so no key is ever fetched.
 */
@WebMvcTest(OrderController.class)
@Import(ResourceServerAutoConfiguration.class)
@TestPropertySource(properties = {
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri="
            + "http://localhost:8080/realms/observability/protocol/openid-connect/certs",
    "app.security.issuer-uri=http://localhost:8080/realms/observability"
})
@DisplayName("OrderController security")
class OrderSecurityTest {

    private static final String NUMBER = "ORD-20260720-A1B2C3D4";
    private static final SimpleGrantedAuthority USER = new SimpleGrantedAuthority("ROLE_USER");
    private static final SimpleGrantedAuthority ADMIN = new SimpleGrantedAuthority("ROLE_ADMIN");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderApplicationService orders;

    @Test
    @DisplayName("refuses an unauthenticated request with 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{n}", NUMBER))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("lets a USER read the API")
    void userCanRead() throws Exception {
        when(orders.search(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/orders").with(jwt().authorities(USER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("forbids a USER from deleting")
    void userCannotDelete() throws Exception {
        mockMvc.perform(delete("/api/v1/orders/{n}", NUMBER).with(jwt().authorities(USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("lets an ADMIN delete")
    void adminCanDelete() throws Exception {
        mockMvc.perform(delete("/api/v1/orders/{n}", NUMBER).with(jwt().authorities(ADMIN)))
                .andExpect(status().isOk());
    }
}
