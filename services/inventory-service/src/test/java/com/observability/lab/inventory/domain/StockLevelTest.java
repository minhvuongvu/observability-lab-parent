package com.observability.lab.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.observability.lab.shared.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StockLevel aggregate")
class StockLevelTest {

    private static final String SKU = "SKU-WIDGET";

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        @DisplayName("records the opening quantity as a receipt")
        void recordsOpeningReceipt() {
            StockLevel stock = StockLevel.create(SKU, 10);

            assertThat(stock.getAvailableQuantity()).isEqualTo(10);
            assertThat(stock.getReservedQuantity()).isZero();
            assertThat(stock.drainPendingMovements())
                    .singleElement()
                    .satisfies(movement -> {
                        assertThat(movement.getMovementType()).isEqualTo(MovementType.RECEIPT);
                        assertThat(movement.getQuantity()).isEqualTo(10);
                    });
        }

        @Test
        @DisplayName("allows a product to be set up before its first delivery")
        void allowsZeroOpeningStock() {
            StockLevel stock = StockLevel.create(SKU, 0);

            assertThat(stock.totalQuantity()).isZero();
            // Nothing happened, so nothing is recorded.
            assertThat(stock.drainPendingMovements()).isEmpty();
        }

        @Test
        @DisplayName("refuses a negative opening quantity")
        void refusesNegativeOpening() {
            assertThatExceptionOfType(BusinessException.class)
                    .isThrownBy(() -> StockLevel.create(SKU, -1));
        }

        @Test
        @DisplayName("refuses a blank SKU")
        void refusesBlankSku() {
            assertThatIllegalArgumentException().isThrownBy(() -> StockLevel.create("  ", 1));
        }
    }

    @Nested
    @DisplayName("reserving")
    class Reserving {

        @Test
        @DisplayName("moves units from available to reserved without changing the total")
        void movesBetweenBuckets() {
            StockLevel stock = StockLevel.create(SKU, 10);
            stock.reserve(4, "ORD-1");

            assertThat(stock.getAvailableQuantity()).isEqualTo(6);
            assertThat(stock.getReservedQuantity()).isEqualTo(4);
            // The invariant that matters: a reservation is a commitment, not a shipment.
            assertThat(stock.totalQuantity()).isEqualTo(10);
        }

        @Test
        @DisplayName("refuses to promise more than is available")
        void refusesOverReservation() {
            StockLevel stock = StockLevel.create(SKU, 3);

            assertThatExceptionOfType(BusinessException.class)
                    .isThrownBy(() -> stock.reserve(4, "ORD-1"))
                    .satisfies(thrown -> {
                        assertThat(thrown.code())
                                .isEqualTo(InventoryErrorCode.INSUFFICIENT_STOCK.code());
                        // A refusal is the service working, so it must not count as a server fault.
                        assertThat(thrown.category().isServerFault()).isFalse();
                    });

            assertThat(stock.getAvailableQuantity()).isEqualTo(3);
            assertThat(stock.getReservedQuantity()).isZero();
        }

        @Test
        @DisplayName("records the order that caused the reservation")
        void recordsReference() {
            StockLevel stock = StockLevel.create(SKU, 10);
            stock.drainPendingMovements();
            stock.reserve(4, "ORD-42");

            assertThat(stock.drainPendingMovements()).singleElement().satisfies(movement -> {
                assertThat(movement.getMovementType()).isEqualTo(MovementType.RESERVATION);
                // Without this the trail records that stock moved but not why.
                assertThat(movement.getReference()).isEqualTo("ORD-42");
            });
        }
    }

    @Nested
    @DisplayName("releasing")
    class Releasing {

        @Test
        @DisplayName("returns reserved units to available")
        void returnsToAvailable() {
            StockLevel stock = StockLevel.create(SKU, 10);
            stock.reserve(4, "ORD-1");
            stock.release(4, "ORD-1");

            assertThat(stock.getAvailableQuantity()).isEqualTo(10);
            assertThat(stock.getReservedQuantity()).isZero();
        }

        @Test
        @DisplayName("refuses to release more than was ever reserved")
        void refusesOverRelease() {
            StockLevel stock = StockLevel.create(SKU, 10);
            stock.reserve(2, "ORD-1");

            assertThatExceptionOfType(BusinessException.class)
                    .isThrownBy(() -> stock.release(3, "ORD-1"))
                    .satisfies(thrown -> assertThat(thrown.code())
                            .isEqualTo(InventoryErrorCode.INSUFFICIENT_RESERVATION.code()));
        }
    }

    @Nested
    @DisplayName("removal")
    class Removal {

        @Test
        @DisplayName("is allowed only when nothing is reserved")
        void onlyWhenUnreserved() {
            StockLevel stock = StockLevel.create(SKU, 10);
            assertThat(stock.isRemovable()).isTrue();

            stock.reserve(1, "ORD-1");
            // Removing now would strand an order promised units the system would forget about.
            assertThat(stock.isRemovable()).isFalse();

            stock.release(1, "ORD-1");
            assertThat(stock.isRemovable()).isTrue();
        }
    }

    @Nested
    @DisplayName("pending movements")
    class PendingMovements {

        @Test
        @DisplayName("are handed over once and then forgotten")
        void drainedOnce() {
            StockLevel stock = StockLevel.create(SKU, 10);

            assertThat(stock.drainPendingMovements()).hasSize(1);
            // A second drain must not re-emit them, or saving twice would double the trail.
            assertThat(stock.drainPendingMovements()).isEmpty();
        }

        @Test
        @DisplayName("accumulate one entry per change")
        void oneEntryPerChange() {
            StockLevel stock = StockLevel.create(SKU, 10);
            stock.reserve(2, "ORD-1");
            stock.release(1, "ORD-1");
            stock.adjust(5, "stock-count");

            assertThat(stock.drainPendingMovements())
                    .extracting(StockMovement::getMovementType)
                    .containsExactly(MovementType.RECEIPT, MovementType.RESERVATION,
                            MovementType.RELEASE, MovementType.ADJUSTMENT);
        }
    }
}
