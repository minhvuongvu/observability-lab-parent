package com.observability.lab.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.observability.lab.inventory.domain.InventoryErrorCode;
import com.observability.lab.inventory.domain.StockLevel;
import com.observability.lab.shared.exception.BusinessException;
import com.observability.lab.shared.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.CacheManager;

@ExtendWith(MockitoExtension.class)
// The cache manager is consulted only on the paths that change several SKUs at once, so it is
// unused in most of these tests.
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StockApplicationService")
class StockApplicationServiceTest {

    private static final String EVENT_ID = "evt-1";
    private static final String ORDER = "ORD-20260720-A1B2C3D4";

    @Mock
    private StockLevelRepository stockLevels;

    @Mock
    private ProcessedEventRepository processedEvents;

    @Mock
    private CacheManager cacheManager;

    private StockApplicationService service;

    @BeforeEach
    void setUp() {
        service = new StockApplicationService(stockLevels, processedEvents, cacheManager);
        when(stockLevels.save(any(StockLevel.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static StockLevel stock(String sku, int available) {
        return StockLevel.create(sku, available);
    }

    @Nested
    @DisplayName("reserving for an order")
    class ReserveForOrder {

        @Test
        @DisplayName("reserves every line when all are satisfiable")
        void reservesAllLines() {
            StockLevel widget = stock("SKU-A", 10);
            StockLevel gizmo = stock("SKU-B", 5);
            when(processedEvents.hasProcessed(EVENT_ID)).thenReturn(false);
            when(stockLevels.findAllByProductSkuIn(any())).thenReturn(List.of(widget, gizmo));

            ReservationResult result = service.reserveForOrder(EVENT_ID, ORDER,
                    List.of(new ReservationLine("SKU-A", 3), new ReservationLine("SKU-B", 2)));

            assertThat(result.outcome()).isEqualTo(ReservationResult.Outcome.RESERVED);
            assertThat(widget.getReservedQuantity()).isEqualTo(3);
            assertThat(gizmo.getReservedQuantity()).isEqualTo(2);
            verify(processedEvents).markProcessed(EVENT_ID, "order-created",
                    new ProcessedEventRepository.RecordedOutcome(
                            ReservationResult.Outcome.RESERVED, null));
        }

        @Test
        @DisplayName("ignores a redelivery of an event it has already applied")
        void isIdempotent() {
            when(processedEvents.hasProcessed(EVENT_ID)).thenReturn(true);
            when(processedEvents.outcomeOf(EVENT_ID)).thenReturn(Optional.of(
                    new ProcessedEventRepository.RecordedOutcome(
                            ReservationResult.Outcome.RESERVED, null)));

            ReservationResult result = service.reserveForOrder(EVENT_ID, ORDER,
                    List.of(new ReservationLine("SKU-A", 3)));

            assertThat(result.outcome()).isEqualTo(ReservationResult.Outcome.ALREADY_PROCESSED);
            // At-least-once delivery makes this the difference between correct stock and stock
            // that quietly drifts every time a consumer restarts at the wrong moment.
            verify(stockLevels, never()).findAllByProductSkuIn(any());
            verify(stockLevels, never()).save(any());
        }

        @Test
        @DisplayName("replays the original decision on a redelivery, so the settlement can be re-announced")
        void redeliveryReplaysTheRecordedSettlement() {
            when(processedEvents.hasProcessed(EVENT_ID)).thenReturn(true);
            when(processedEvents.outcomeOf(EVENT_ID)).thenReturn(Optional.of(
                    new ProcessedEventRepository.RecordedOutcome(
                            ReservationResult.Outcome.REJECTED, "SKU-A (requested 9, available 1)")));

            ReservationResult result = service.reserveForOrder(EVENT_ID, ORDER,
                    List.of(new ReservationLine("SKU-A", 9)));

            // The stock is untouched, but the answer survives: without this the Order Service would
            // never hear the outcome of an event whose first announcement failed.
            assertThat(result.outcome()).isEqualTo(ReservationResult.Outcome.ALREADY_PROCESSED);
            assertThat(result.settlement()).isEqualTo(ReservationResult.Outcome.REJECTED);
            assertThat(result.shortages()).singleElement().asString().contains("SKU-A");
            assertThat(result.hasSettlement()).isTrue();
        }

        @Test
        @DisplayName("has nothing to announce for a redelivery recorded before outcomes were stored")
        void redeliveryWithoutRecordedOutcomeAnnouncesNothing() {
            when(processedEvents.hasProcessed(EVENT_ID)).thenReturn(true);
            when(processedEvents.outcomeOf(EVENT_ID)).thenReturn(Optional.empty());

            ReservationResult result = service.reserveForOrder(EVENT_ID, ORDER,
                    List.of(new ReservationLine("SKU-A", 3)));

            assertThat(result.hasSettlement()).isFalse();
        }

        @Test
        @DisplayName("reserves nothing at all when one line is short")
        void isAllOrNothing() {
            StockLevel plentiful = stock("SKU-A", 10);
            StockLevel scarce = stock("SKU-B", 1);
            when(processedEvents.hasProcessed(EVENT_ID)).thenReturn(false);
            when(stockLevels.findAllByProductSkuIn(any())).thenReturn(List.of(plentiful, scarce));

            ReservationResult result = service.reserveForOrder(EVENT_ID, ORDER,
                    List.of(new ReservationLine("SKU-A", 3), new ReservationLine("SKU-B", 5)));

            assertThat(result.outcome()).isEqualTo(ReservationResult.Outcome.REJECTED);
            assertThat(result.shortages()).singleElement().asString().contains("SKU-B");
            // The satisfiable line must not be reserved either: a half-promised order is worse
            // than a refused one, because nothing records which half.
            assertThat(plentiful.getReservedQuantity()).isZero();
            assertThat(scarce.getReservedQuantity()).isZero();
        }

        @Test
        @DisplayName("treats an untracked product as a shortage rather than a crash")
        void untrackedProductIsAShortage() {
            when(processedEvents.hasProcessed(EVENT_ID)).thenReturn(false);
            when(stockLevels.findAllByProductSkuIn(any())).thenReturn(List.of());

            ReservationResult result = service.reserveForOrder(EVENT_ID, ORDER,
                    List.of(new ReservationLine("SKU-UNKNOWN", 1)));

            assertThat(result.outcome()).isEqualTo(ReservationResult.Outcome.REJECTED);
            assertThat(result.shortages()).singleElement().asString().contains("not tracked");
        }

        @Test
        @DisplayName("records a rejection as handled, so it is not retried forever")
        void rejectionIsStillRecorded() {
            when(processedEvents.hasProcessed(EVENT_ID)).thenReturn(false);
            when(stockLevels.findAllByProductSkuIn(any())).thenReturn(List.of(stock("SKU-A", 1)));

            service.reserveForOrder(EVENT_ID, ORDER, List.of(new ReservationLine("SKU-A", 9)));

            // Redelivering would produce the same shortage every time; marking it handled is what
            // stops the partition being blocked by an answer that will never change.
            verify(processedEvents).markProcessed(eq(EVENT_ID), eq("order-created"),
                    argThat(recorded ->
                            recorded.outcome() == ReservationResult.Outcome.REJECTED
                                    && recorded.detail().contains("SKU-A")));
        }
    }

    @Nested
    @DisplayName("CRUD")
    class Crud {

        @Test
        @DisplayName("refuses to track the same product twice")
        void refusesDuplicate() {
            when(stockLevels.existsByProductSku("SKU-A")).thenReturn(true);

            assertThatExceptionOfType(BusinessException.class)
                    .isThrownBy(() -> service.create("SKU-A", 5))
                    .satisfies(thrown -> assertThat(thrown.code())
                            .isEqualTo(InventoryErrorCode.DUPLICATE_SKU.code()));
        }

        @Test
        @DisplayName("reports an untracked product as not found, not as an internal error")
        void missingProduct() {
            when(stockLevels.findByProductSku(anyString())).thenReturn(Optional.empty());

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> service.findByProductSku("SKU-A"))
                    .satisfies(thrown -> assertThat(thrown.code())
                            .isEqualTo(InventoryErrorCode.STOCK_NOT_FOUND.code()));
        }

        @Test
        @DisplayName("refuses to stop tracking a product with outstanding reservations")
        void refusesRemovalWhileReserved() {
            StockLevel reserved = stock("SKU-A", 10);
            reserved.reserve(2, ORDER);
            when(stockLevels.findByProductSku("SKU-A")).thenReturn(Optional.of(reserved));

            assertThatExceptionOfType(BusinessException.class)
                    .isThrownBy(() -> service.delete("SKU-A"))
                    .satisfies(thrown -> assertThat(thrown.code())
                            .isEqualTo(InventoryErrorCode.STOCK_STILL_RESERVED.code()));

            verify(stockLevels, never()).delete(any());
        }

        @Test
        @DisplayName("removes a product once nothing is reserved")
        void removesUnreservedProduct() {
            StockLevel free = stock("SKU-A", 10);
            when(stockLevels.findByProductSku("SKU-A")).thenReturn(Optional.of(free));

            service.delete("SKU-A");

            verify(stockLevels).delete(free);
        }
    }
}
