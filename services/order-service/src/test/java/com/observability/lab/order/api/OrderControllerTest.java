package com.observability.lab.order.api;

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
import com.observability.lab.order.application.OrderApplicationService;
import com.observability.lab.order.application.OrderView;
import com.observability.lab.order.domain.OrderErrorCode;
import com.observability.lab.order.domain.OrderStatus;
import com.observability.lab.shared.exception.BusinessException;
import com.observability.lab.shared.exception.ConstraintViolationAdvice;
import com.observability.lab.shared.exception.GlobalExceptionHandler;
import com.observability.lab.shared.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice.
 *
 * <p>The platform advices are imported explicitly. {@code @WebMvcTest} loads only the
 * auto-configurations belonging to the web slice, and the shared library's are not among them — so
 * without this import the test would exercise Spring's default whitelabel error body and prove
 * nothing about the error contract this service publishes.
 *
 * <p>Security filters are switched off here ({@code addFilters = false}). This slice covers the
 * controller's binding, validation and error mapping, not authentication; the resource-server rules
 * added in step 07 are proved separately in {@link OrderSecurityTest}.
 */
@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, ConstraintViolationAdvice.class})
@DisplayName("OrderController")
class OrderControllerTest {

    private static final String NUMBER = "ORD-20260720-A1B2C3D4";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderApplicationService orders;

    private static OrderView view(OrderStatus status) {
        return new OrderView(NUMBER, "C-1", status, new BigDecimal("10.00"), "EUR",
                List.of(new OrderView.OrderItemView("SKU-1", 2, new BigDecimal("5.00"),
                        new BigDecimal("10.00"))),
                Instant.parse("2026-07-20T10:00:00Z"), null);
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Nested
    @DisplayName("POST /api/v1/orders")
    class Create {

        @Test
        @DisplayName("returns 201 with the envelope and a Location header")
        void createsOrder() throws Exception {
            when(orders.create(any())).thenReturn(view(OrderStatus.PENDING));

            CreateOrderRequest request = new CreateOrderRequest("C-1", "EUR",
                    List.of(new OrderItemRequest("SKU-1", 2, new BigDecimal("5.00"))));

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(request)))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/orders/" + NUMBER))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.orderNumber").value(NUMBER))
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    // Present on every response, so a caller can always quote it.
                    .andExpect(jsonPath("$.meta.timestamp").exists());
        }

        @Test
        @DisplayName("rejects a blank customer with a field-level violation")
        void rejectsBlankCustomer() throws Exception {
            CreateOrderRequest request = new CreateOrderRequest("  ", "EUR",
                    List.of(new OrderItemRequest("SKU-1", 2, new BigDecimal("5.00"))));

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("PLT-4000"))
                    .andExpect(jsonPath("$.error.violations[0].field").value("customerId"));
        }

        @Test
        @DisplayName("rejects an order with no lines")
        void rejectsEmptyItems() throws Exception {
            CreateOrderRequest request = new CreateOrderRequest("C-1", "EUR", List.of());

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.violations[0].field").value("items"));
        }

        @Test
        @DisplayName("cascades validation into the line items")
        void rejectsInvalidLine() throws Exception {
            // Without @Valid on the list, the item constraints never run and a negative price
            // reaches the database.
            CreateOrderRequest request = new CreateOrderRequest("C-1", "EUR",
                    List.of(new OrderItemRequest("SKU-1", -3, new BigDecimal("5.00"))));

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.violations[0].field").value("items[0].quantity"));
        }

        @Test
        @DisplayName("rejects a currency that is not a 3-letter code")
        void rejectsBadCurrency() throws Exception {
            CreateOrderRequest request = new CreateOrderRequest("C-1", "EURO",
                    List.of(new OrderItemRequest("SKU-1", 1, new BigDecimal("5.00"))));

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.violations[0].field").value("currency"));
        }

        @Test
        @DisplayName("reports an unparseable body as a client error, not a server error")
        void rejectsMalformedJson() throws Exception {
            mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("PLT-4001"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/orders")
    class Read {

        @Test
        @DisplayName("returns one order in the envelope")
        void readsOne() throws Exception {
            when(orders.findByOrderNumber(NUMBER)).thenReturn(view(OrderStatus.CONFIRMED));

            mockMvc.perform(get("/api/v1/orders/{number}", NUMBER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.data.items[0].lineTotal").value(10.00));
        }

        @Test
        @DisplayName("maps a missing order to 404 with the context error code")
        void missingOrder() throws Exception {
            when(orders.findByOrderNumber(NUMBER)).thenThrow(
                    new ResourceNotFoundException(OrderErrorCode.ORDER_NOT_FOUND, "not there"));

            mockMvc.perform(get("/api/v1/orders/{number}", NUMBER))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ORD-4040"));
        }

        @Test
        @DisplayName("returns a stable page shape rather than Spring Data's own")
        void listsOrders() throws Exception {
            Page<OrderView> page = new PageImpl<>(List.of(view(OrderStatus.PENDING)),
                    PageRequest.of(0, 20), 1);
            when(orders.search(eq("C-1"), eq(OrderStatus.PENDING), any())).thenReturn(page);

            mockMvc.perform(get("/api/v1/orders")
                            .param("customerId", "C-1")
                            .param("status", "PENDING"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].orderNumber").value(NUMBER))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.totalPages").value(1))
                    .andExpect(jsonPath("$.data.first").value(true))
                    .andExpect(jsonPath("$.data.last").value(true));
        }

        @Test
        @DisplayName("rejects an unknown status value as a client error")
        void rejectsUnknownStatus() throws Exception {
            mockMvc.perform(get("/api/v1/orders").param("status", "NONSENSE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("PLT-4002"));
        }
    }

    @Nested
    @DisplayName("state changes")
    class StateChanges {

        @Test
        @DisplayName("cancels an order")
        void cancels() throws Exception {
            when(orders.cancel(NUMBER)).thenReturn(view(OrderStatus.CANCELLED));

            mockMvc.perform(post("/api/v1/orders/{number}/cancel", NUMBER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }

        @Test
        @DisplayName("maps a refused business rule to 422, not 400 or 500")
        void refusedRule() throws Exception {
            when(orders.cancel(NUMBER)).thenThrow(new BusinessException(
                    OrderErrorCode.ILLEGAL_STATUS_TRANSITION, "already rejected"));

            mockMvc.perform(post("/api/v1/orders/{number}/cancel", NUMBER))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("ORD-4220"))
                    // The message reaches the caller because this is their fault, not ours.
                    .andExpect(jsonPath("$.error.message").value("already rejected"));
        }

        @Test
        @DisplayName("deletes an order and still returns the envelope")
        void deletes() throws Exception {
            mockMvc.perform(delete("/api/v1/orders/{number}", NUMBER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.meta").exists());
        }

        @Test
        @DisplayName("refuses to delete a live order with 422")
        void refusesDeletingLiveOrder() throws Exception {
            doThrow(new BusinessException(OrderErrorCode.ORDER_NOT_DELETABLE, "still pending"))
                    .when(orders).delete(NUMBER);

            mockMvc.perform(delete("/api/v1/orders/{number}", NUMBER))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("ORD-4221"));
        }
    }
}
