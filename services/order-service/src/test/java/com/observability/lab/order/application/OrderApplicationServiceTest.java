package com.observability.lab.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.observability.lab.order.domain.Order;
import com.observability.lab.order.domain.OrderErrorCode;
import com.observability.lab.order.domain.OrderItem;
import com.observability.lab.order.domain.OrderStatus;
import com.observability.lab.shared.exception.BusinessException;
import com.observability.lab.shared.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderApplicationService")
class OrderApplicationServiceTest {

    private static final String NUMBER = "ORD-20260720-A1B2C3D4";

    @Mock
    private OrderRepository orders;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private InvoiceArchive invoices;

    @Mock
    private InvoiceRenderer invoiceRenderer;

    private OrderApplicationService service;

    @BeforeEach
    void setUp() {
        service = new OrderApplicationService(orders, new OrderNumberGenerator(), events,
                invoices, invoiceRenderer, Duration.ofMinutes(15));
    }

    private static Order existingOrder() {
        return Order.create(NUMBER, "C-1", "EUR",
                List.of(OrderItem.of("SKU-1", 2, new BigDecimal("5.00"))));
    }

    private static CreateOrderCommand command() {
        return new CreateOrderCommand("C-1", "EUR",
                List.of(new CreateOrderCommand.Line("SKU-1", 2, new BigDecimal("5.00"))));
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("persists the order and returns the computed total")
        void persistsAndReturns() {
            when(orders.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));

            OrderView view = service.create(command());

            assertThat(view.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(view.totalAmount()).isEqualByComparingTo("10.00");
            assertThat(view.items()).singleElement()
                    .satisfies(item -> assertThat(item.lineTotal()).isEqualByComparingTo("10.00"));
        }

        @Test
        @DisplayName("raises an event describing the order that was saved")
        void raisesEvent() {
            when(orders.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));

            OrderView view = service.create(command());

            ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
            verify(events).publishEvent(published.capture());

            assertThat(published.getValue()).isInstanceOfSatisfying(OrderCreatedEvent.class, event -> {
                assertThat(event.orderNumber()).isEqualTo(view.orderNumber());
                assertThat(event.items()).singleElement()
                        .satisfies(item -> {
                            assertThat(item.productSku()).isEqualTo("SKU-1");
                            assertThat(item.quantity()).isEqualTo(2);
                        });
                // Every publication is uniquely identifiable, which is what lets a consumer
                // recognise a redelivery under at-least-once semantics.
                assertThat(event.eventId()).isNotBlank();
            });
        }

        @Test
        @DisplayName("generates a distinct order number per order")
        void generatesDistinctNumbers() {
            when(orders.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));

            assertThat(service.create(command()).orderNumber())
                    .isNotEqualTo(service.create(command()).orderNumber());
        }
    }

    @Nested
    @DisplayName("read")
    class Read {

        @Test
        @DisplayName("reports a missing order as not found, not as an internal error")
        void missingOrder() {
            when(orders.findByOrderNumber(NUMBER)).thenReturn(Optional.empty());

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> service.findByOrderNumber(NUMBER))
                    .satisfies(thrown -> {
                        assertThat(thrown.code()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND.code());
                        assertThat(thrown.category().isServerFault()).isFalse();
                    });
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("moves the order to CANCELLED and saves it")
        void cancels() {
            Order order = existingOrder();
            when(orders.findByOrderNumber(NUMBER)).thenReturn(Optional.of(order));
            when(orders.save(order)).thenReturn(order);

            assertThat(service.cancel(NUMBER).status()).isEqualTo(OrderStatus.CANCELLED);
            verify(orders).save(order);
        }

        @Test
        @DisplayName("lets the aggregate refuse an illegal transition, and saves nothing")
        void refusesIllegalTransition() {
            Order order = existingOrder();
            order.reject();
            when(orders.findByOrderNumber(NUMBER)).thenReturn(Optional.of(order));

            assertThatExceptionOfType(BusinessException.class)
                    .isThrownBy(() -> service.cancel(NUMBER));

            verify(orders, never()).save(any());
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("refuses to remove an order that is still live")
        void refusesLiveOrder() {
            // Deleting a pending order would strand any stock reservation made for it.
            when(orders.findByOrderNumber(NUMBER)).thenReturn(Optional.of(existingOrder()));

            assertThatExceptionOfType(BusinessException.class)
                    .isThrownBy(() -> service.delete(NUMBER))
                    .satisfies(thrown -> assertThat(thrown.code())
                            .isEqualTo(OrderErrorCode.ORDER_NOT_DELETABLE.code()));

            verify(orders, never()).delete(any());
        }

        @Test
        @DisplayName("removes an order that has already been cancelled")
        void removesCancelledOrder() {
            Order order = existingOrder();
            order.cancel();
            when(orders.findByOrderNumber(NUMBER)).thenReturn(Optional.of(order));

            service.delete(NUMBER);

            verify(orders).delete(order);
        }
    }
}
