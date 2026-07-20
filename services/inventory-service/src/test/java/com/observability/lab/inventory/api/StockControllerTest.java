package com.observability.lab.inventory.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.observability.lab.inventory.application.StockApplicationService;
import com.observability.lab.inventory.application.StockLevelView;
import com.observability.lab.inventory.domain.InventoryErrorCode;
import com.observability.lab.shared.exception.BusinessException;
import com.observability.lab.shared.exception.ConstraintViolationAdvice;
import com.observability.lab.shared.exception.GlobalExceptionHandler;
import com.observability.lab.shared.exception.ResourceNotFoundException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice.
 *
 * <p>The platform advices are imported explicitly: {@code @WebMvcTest} loads only the
 * auto-configurations belonging to the web slice, and the shared library's are not among them.
 * Without the import this would assert against Spring's default whitelabel error body and prove
 * nothing about the error contract the service publishes.
 */
@WebMvcTest(StockController.class)
@Import({GlobalExceptionHandler.class, ConstraintViolationAdvice.class})
@DisplayName("StockController")
class StockControllerTest {

    private static final String SKU = "SKU-WIDGET";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private StockApplicationService stock;

    private static StockLevelView view(int available, int reserved) {
        return new StockLevelView(SKU, available, reserved, available + reserved,
                Instant.parse("2026-07-20T10:00:00Z"), null);
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Nested
    @DisplayName("POST /api/v1/stock")
    class Create {

        @Test
        @DisplayName("returns 201 with the envelope and a Location header")
        void createsStockLevel() throws Exception {
            when(stock.create(eq(SKU), eq(10))).thenReturn(view(10, 0));

            mockMvc.perform(post("/api/v1/stock")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CreateStockLevelRequest(SKU, 10))))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/stock/" + SKU))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.availableQuantity").value(10))
                    .andExpect(jsonPath("$.meta.timestamp").exists());
        }

        @Test
        @DisplayName("rejects a negative opening quantity")
        void rejectsNegativeQuantity() throws Exception {
            mockMvc.perform(post("/api/v1/stock")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CreateStockLevelRequest(SKU, -1))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("PLT-4000"))
                    .andExpect(jsonPath("$.error.violations[0].field").value("initialQuantity"));
        }

        @Test
        @DisplayName("maps a duplicate product to 409, not 500")
        void duplicateIsAConflict() throws Exception {
            when(stock.create(any(), any(Integer.class))).thenThrow(
                    new BusinessException(InventoryErrorCode.DUPLICATE_SKU, "already tracked"));

            mockMvc.perform(post("/api/v1/stock")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CreateStockLevelRequest(SKU, 5))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("INV-4090"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/stock")
    class Read {

        @Test
        @DisplayName("returns the shape the Order Service's client reads")
        void readsOne() throws Exception {
            when(stock.findByProductSku(SKU)).thenReturn(view(6, 4));

            mockMvc.perform(get("/api/v1/stock/{sku}", SKU))
                    .andExpect(status().isOk())
                    // These three field names are a published contract between the two services.
                    .andExpect(jsonPath("$.data.productSku").value(SKU))
                    .andExpect(jsonPath("$.data.availableQuantity").value(6))
                    .andExpect(jsonPath("$.data.reservedQuantity").value(4))
                    .andExpect(jsonPath("$.data.totalQuantity").value(10));
        }

        @Test
        @DisplayName("maps an untracked product to 404 with the context error code")
        void untrackedProduct() throws Exception {
            when(stock.findByProductSku(SKU)).thenThrow(new ResourceNotFoundException(
                    InventoryErrorCode.STOCK_NOT_FOUND, "not tracked"));

            mockMvc.perform(get("/api/v1/stock/{sku}", SKU))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("INV-4040"));
        }
    }

    @Nested
    @DisplayName("quantity movements")
    class Movements {

        @Test
        @DisplayName("receives units")
        void receives() throws Exception {
            when(stock.receive(SKU, 5, "delivery-1")).thenReturn(view(15, 0));

            mockMvc.perform(post("/api/v1/stock/{sku}/receive", SKU)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new StockQuantityRequest(5, "delivery-1"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.availableQuantity").value(15));
        }

        @Test
        @DisplayName("requires a reference, so no movement is unexplained")
        void requiresReference() throws Exception {
            mockMvc.perform(post("/api/v1/stock/{sku}/receive", SKU)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new StockQuantityRequest(5, "  "))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.violations[0].field").value("reference"));
        }

        @Test
        @DisplayName("maps an over-release to 422, because it is a rule and not a fault")
        void overReleaseIsARuleRefusal() throws Exception {
            when(stock.release(any(), any(Integer.class), any())).thenThrow(new BusinessException(
                    InventoryErrorCode.INSUFFICIENT_RESERVATION, "only 2 reserved"));

            mockMvc.perform(post("/api/v1/stock/{sku}/release", SKU)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new StockQuantityRequest(3, "ORD-1"))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("INV-4221"))
                    .andExpect(jsonPath("$.error.message").value("only 2 reserved"));
        }

        @Test
        @DisplayName("rejects a non-positive quantity")
        void rejectsZeroQuantity() throws Exception {
            mockMvc.perform(post("/api/v1/stock/{sku}/adjust", SKU)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new StockQuantityRequest(0, "count"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.violations[0].field").value("quantity"));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/stock/{sku}")
    class Delete {

        @Test
        @DisplayName("removes a product and still returns the envelope")
        void removes() throws Exception {
            mockMvc.perform(delete("/api/v1/stock/{sku}", SKU))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.meta").exists());
        }

        @Test
        @DisplayName("refuses while units are still reserved")
        void refusesWhileReserved() throws Exception {
            doThrow(new BusinessException(InventoryErrorCode.STOCK_STILL_RESERVED, "4 reserved"))
                    .when(stock).delete(SKU);

            mockMvc.perform(delete("/api/v1/stock/{sku}", SKU))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("INV-4222"));
        }
    }
}
