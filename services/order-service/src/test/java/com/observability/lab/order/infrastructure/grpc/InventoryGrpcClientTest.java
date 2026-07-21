package com.observability.lab.order.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.observability.lab.inventory.grpc.v1.BatchCheckStockRequest;
import com.observability.lab.inventory.grpc.v1.BatchCheckStockResponse;
import com.observability.lab.inventory.grpc.v1.CheckStockResponse;
import com.observability.lab.inventory.grpc.v1.InventoryServiceGrpc;
import com.observability.lab.inventory.grpc.v1.ReservationOutcome;
import com.observability.lab.inventory.grpc.v1.ReserveStockRequest;
import com.observability.lab.inventory.grpc.v1.ReserveStockResponse;
import com.observability.lab.order.application.AvailabilityView;
import com.observability.lab.order.application.CreateOrderCommand;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The consumer's half of the hop: deadlines, the circuit breaker, and what happens when the provider
 * does not answer.
 *
 * <p>The fallbacks are the subject. A degraded answer is a design decision with consequences a user
 * sees — "cannot tell" rather than a confident and wrong "out of stock", and a {@code PENDING} order
 * rather than a failed one — so it is worth asserting rather than assuming.
 */
@DisplayName("InventoryGrpcClient")
class InventoryGrpcClientTest {

    private final AtomicReference<Status> nextFailure = new AtomicReference<>();
    private final AtomicInteger callsReceived = new AtomicInteger();

    private Server server;
    private ManagedChannel channel;
    private CircuitBreaker breaker;
    private InventoryGrpcClient client;

    @BeforeEach
    void startServer() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new StubProvider())
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        // A tiny window so a handful of calls is enough to open the circuit in a test, with the
        // same status classification the real configuration uses.
        breaker = CircuitBreaker.of("inventory-grpc-test", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .recordException(failure -> failure instanceof StatusRuntimeException status
                        && Set.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED,
                                Status.Code.RESOURCE_EXHAUSTED).contains(status.getStatus().getCode()))
                .build());

        client = new InventoryGrpcClient(
                InventoryServiceGrpc.newBlockingStub(channel),
                breaker,
                properties());
    }

    @AfterEach
    void stopServer() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Nested
    @DisplayName("availability")
    class Availability {

        @Test
        @DisplayName("maps a batch answer back onto the requested lines, in order")
        void mapsResultsPositionally() {
            List<AvailabilityView> views = client.batchCheck(List.of(line("SKU-A", 2), line("SKU-B", 9)));

            assertThat(views).hasSize(2);
            assertThat(views.get(0).productSku()).isEqualTo("SKU-A");
            assertThat(views.get(0).sufficient()).isTrue();
            assertThat(views.get(0).known()).isTrue();
            // The stub reports SKU-B as untracked; the caller must see "unknown", never "zero".
            assertThat(views.get(1).known()).isFalse();
            assertThat(views.get(1).sufficient()).isFalse();
        }

        @Test
        @DisplayName("the whole basket degrades to unknown when the provider is unreachable")
        void degradesRatherThanFailing() {
            // "Availability unavailable" is honest. A confident "out of stock" would lose an order
            // that could have been placed.
            nextFailure.set(Status.UNAVAILABLE);

            List<AvailabilityView> views = client.batchCheck(List.of(line("SKU-A", 1), line("SKU-B", 1)));

            assertThat(views).hasSize(2);
            assertThat(views).allMatch(view -> !view.known());
            assertThat(views).allMatch(view -> !view.sufficient());
        }

        @Test
        @DisplayName("a business status is not a fault and still yields the degraded answer")
        void doesNotPropagateStatusExceptions() {
            nextFailure.set(Status.NOT_FOUND);

            assertThat(client.batchCheck(List.of(line("SKU-A", 1)))).allMatch(view -> !view.known());
        }
    }

    @Nested
    @DisplayName("express reservation")
    class Express {

        @Test
        @DisplayName("a reservation is reported so the order can be confirmed immediately")
        void reportsReserved() {
            ExpressReservation reservation =
                    client.reserve("evt-1", "ORD-1", List.of(line("SKU-A", 1)));

            assertThat(reservation.outcome()).isEqualTo(ExpressReservation.Outcome.RESERVED);
            assertThat(reservation.settled()).isTrue();
        }

        @Test
        @DisplayName("no answer is UNAVAILABLE, which leaves the order to the Kafka path")
        void fallsThroughOnFailure() {
            // Nothing is lost: the outbox row is already committed and will reserve the same stock
            // under the same event id.
            nextFailure.set(Status.DEADLINE_EXCEEDED);

            ExpressReservation reservation =
                    client.reserve("evt-1", "ORD-1", List.of(line("SKU-A", 1)));

            assertThat(reservation.outcome()).isEqualTo(ExpressReservation.Outcome.UNAVAILABLE);
            assertThat(reservation.settled()).isFalse();
        }
    }

    @Nested
    @DisplayName("circuit breaker")
    class Breaker {

        @Test
        @DisplayName("stops contacting a dependency that is down, and still answers")
        void opensAndFallsBack() {
            nextFailure.set(Status.UNAVAILABLE);
            client.batchCheck(List.of(line("SKU-A", 1)));
            client.batchCheck(List.of(line("SKU-A", 1)));

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            int before = callsReceived.get();
            List<AvailabilityView> views = client.batchCheck(List.of(line("SKU-A", 1)));

            // The dependency is not contacted at all - that is the difference between a breaker and
            // a retry policy - and the caller still gets a usable answer.
            assertThat(callsReceived.get()).isEqualTo(before);
            assertThat(views).allMatch(view -> !view.known());
        }

        @Test
        @DisplayName("a business outcome must never open the circuit")
        void businessOutcomesDoNotCount() {
            // The most common misconfiguration in practice, and it fails in the worst direction: a
            // catalogue full of untracked SKUs cuts off a perfectly healthy dependency.
            nextFailure.set(Status.NOT_FOUND);
            client.batchCheck(List.of(line("SKU-A", 1)));
            client.batchCheck(List.of(line("SKU-A", 1)));
            client.batchCheck(List.of(line("SKU-A", 1)));

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    // --- Harness -------------------------------------------------------------

    /** Answers with whatever the test asked for: a canned response, or a chosen status. */
    private final class StubProvider extends InventoryServiceGrpc.InventoryServiceImplBase {

        @Override
        public void batchCheckStock(BatchCheckStockRequest request,
                StreamObserver<BatchCheckStockResponse> observer) {
            callsReceived.incrementAndGet();
            Status failure = nextFailure.get();
            if (failure != null) {
                observer.onError(failure.asRuntimeException());
                return;
            }

            BatchCheckStockResponse.Builder response = BatchCheckStockResponse.newBuilder();
            for (int i = 0; i < request.getItemsCount(); i++) {
                var item = request.getItems(i);
                boolean tracked = !"SKU-B".equals(item.getProductSku());
                response.addResults(CheckStockResponse.newBuilder()
                        .setProductSku(item.getProductSku())
                        .setAvailableQuantity(tracked ? 50 : 0)
                        .setTracked(tracked)
                        .setSufficient(tracked && 50 >= item.getRequestedQuantity())
                        .build());
            }
            observer.onNext(response.build());
            observer.onCompleted();
        }

        @Override
        public void reserveStock(ReserveStockRequest request,
                StreamObserver<ReserveStockResponse> observer) {
            callsReceived.incrementAndGet();
            Status failure = nextFailure.get();
            if (failure != null) {
                observer.onError(failure.asRuntimeException());
                return;
            }
            observer.onNext(ReserveStockResponse.newBuilder()
                    .setOutcome(ReservationOutcome.RESERVATION_OUTCOME_RESERVED)
                    .build());
            observer.onCompleted();
        }
    }

    private static CreateOrderCommand.Line line(String sku, int quantity) {
        return new CreateOrderCommand.Line(sku, quantity, BigDecimal.ONE);
    }

    private static InventoryGrpcProperties properties() {
        return new InventoryGrpcProperties("inventory-service", true,
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofMinutes(5),
                4 * 1024 * 1024, Duration.ofSeconds(10), 3, 0.2, null);
    }
}
