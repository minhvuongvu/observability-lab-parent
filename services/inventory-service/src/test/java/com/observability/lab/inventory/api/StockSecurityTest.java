package com.observability.lab.inventory.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.observability.lab.inventory.application.StockApplicationService;
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
 * <p>Proves the shared resource-server policy guards this service too. Both services install the
 * exact same chain from the shared library, so this is the second half of one guarantee: that
 * "protect the API" behaves identically on either side of the Kafka boundary.
 */
@WebMvcTest(StockController.class)
@Import(ResourceServerAutoConfiguration.class)
@TestPropertySource(properties = {
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri="
            + "http://localhost:8080/realms/observability/protocol/openid-connect/certs",
    "app.security.issuer-uri=http://localhost:8080/realms/observability"
})
@DisplayName("StockController security")
class StockSecurityTest {

    private static final String SKU = "SKU-WIDGET";
    private static final SimpleGrantedAuthority USER = new SimpleGrantedAuthority("ROLE_USER");
    private static final SimpleGrantedAuthority ADMIN = new SimpleGrantedAuthority("ROLE_ADMIN");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockApplicationService stock;

    @Test
    @DisplayName("refuses an unauthenticated request with 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/stock/{sku}", SKU))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("lets a USER read the API")
    void userCanRead() throws Exception {
        when(stock.list(any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/stock").with(jwt().authorities(USER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("forbids a USER from deleting")
    void userCannotDelete() throws Exception {
        mockMvc.perform(delete("/api/v1/stock/{sku}", SKU).with(jwt().authorities(USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("lets an ADMIN delete")
    void adminCanDelete() throws Exception {
        mockMvc.perform(delete("/api/v1/stock/{sku}", SKU).with(jwt().authorities(ADMIN)))
                .andExpect(status().isOk());
    }
}
