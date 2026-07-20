package com.observability.lab.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.observability.lab.shared.exception.BusinessException;
import com.observability.lab.shared.exception.ValidationException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Order aggregate")
class OrderTest {

    private static final String NUMBER = "ORD-20260720-A1B2C3D4";
    private static final String CUSTOMER = "C-1";

    private static OrderItem line(String sku, int quantity, String unitPrice) {
        return OrderItem.of(sku, quantity, new BigDecimal(unitPrice));
    }

    private static Order order(OrderItem... lines) {
        return Order.create(NUMBER, CUSTOMER, "eur", List.of(lines));
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        @DisplayName("starts PENDING, because nothing has reserved stock yet")
        void startsPending() {
            assertThat(order(line("SKU-1", 1, "10.00")).getStatus()).isEqualTo(OrderStatus.PENDING);
        }

        @Test
        @DisplayName("computes the total from the lines rather than trusting a caller")
        void computesTotal() {
            Order order = order(line("SKU-1", 3, "10.50"), line("SKU-2", 2, "4.25"));
            // 3 x 10.50 + 2 x 4.25
            assertThat(order.getTotalAmount()).isEqualByComparingTo("40.00");
        }

        @Test
        @DisplayName("stores the total at the scale the column holds")
        void totalIsScaled() {
            // Otherwise the in-memory total and the one read back from the database differ.
            assertThat(order(line("SKU-1", 3, "0.335")).getTotalAmount().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("normalises the currency code to upper case")
        void normalisesCurrency() {
            assertThat(order(line("SKU-1", 1, "1.00")).getCurrency()).isEqualTo("EUR");
        }

        @Test
        @DisplayName("refuses an order with no lines")
        void refusesEmptyOrder() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> Order.create(NUMBER, CUSTOMER, "EUR", List.of()))
                    .satisfies(thrown ->
                            assertThat(thrown.code()).isEqualTo(OrderErrorCode.EMPTY_ORDER.code()));
        }

        @Test
        @DisplayName("refuses a currency that is not a 3-letter code")
        void refusesBadCurrency() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> Order.create(NUMBER, CUSTOMER, "EURO", List.of(line("SKU-1", 1, "1.00"))));
        }

        @Test
        @DisplayName("exposes its lines as an unmodifiable list")
        void linesAreUnmodifiable() {
            // The aggregate guarantees the total matches the lines; a caller that could append a
            // line would silently break that.
            Order order = order(line("SKU-1", 1, "1.00"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> order.getItems().add(line("SKU-2", 1, "1.00")));
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("can be confirmed, then cancelled")
        void confirmThenCancel() {
            Order order = order(line("SKU-1", 1, "1.00"));
            order.confirm();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            order.cancel();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("refuses an illegal move as a business rule, not a technical failure")
        void refusesIllegalTransition() {
            Order order = order(line("SKU-1", 1, "1.00"));
            order.reject();

            assertThatExceptionOfType(BusinessException.class)
                    .isThrownBy(order::confirm)
                    .satisfies(thrown -> {
                        assertThat(thrown.code())
                                .isEqualTo(OrderErrorCode.ILLEGAL_STATUS_TRANSITION.code());
                        // The category is what makes this a 422 logged at INFO rather than a 500
                        // logged at ERROR with a stack trace.
                        assertThat(thrown.category().isServerFault()).isFalse();
                    });
        }

        @Test
        @DisplayName("is deletable only once it is cancelled or rejected")
        void deletableOnlyWhenTerminal() {
            Order pending = order(line("SKU-1", 1, "1.00"));
            assertThat(pending.isDeletable()).isFalse();

            pending.confirm();
            assertThat(pending.isDeletable()).isFalse();

            pending.cancel();
            assertThat(pending.isDeletable()).isTrue();
        }
    }

    @Nested
    @DisplayName("lines")
    class Lines {

        @Test
        @DisplayName("compute their own total")
        void lineTotal() {
            assertThat(line("SKU-1", 3, "1.50").lineTotal()).isEqualByComparingTo("4.50");
        }

        @Test
        @DisplayName("refuse a non-positive quantity")
        void refusesBadQuantity() {
            assertThatIllegalArgumentException().isThrownBy(() -> line("SKU-1", 0, "1.00"));
            assertThatIllegalArgumentException().isThrownBy(() -> line("SKU-1", -1, "1.00"));
        }

        @Test
        @DisplayName("refuse a negative price")
        void refusesNegativePrice() {
            assertThatIllegalArgumentException().isThrownBy(() -> line("SKU-1", 1, "-0.01"));
        }

        @Test
        @DisplayName("refuse a blank SKU")
        void refusesBlankSku() {
            assertThatIllegalArgumentException().isThrownBy(() -> line("  ", 1, "1.00"));
        }
    }
}
