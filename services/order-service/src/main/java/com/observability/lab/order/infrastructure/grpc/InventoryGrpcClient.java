package com.observability.lab.order.infrastructure.grpc;

import com.observability.lab.inventory.grpc.v1.BatchCheckStockRequest;
import com.observability.lab.inventory.grpc.v1.BatchCheckStockResponse;
import com.observability.lab.inventory.grpc.v1.CheckStockRequest;
import com.observability.lab.inventory.grpc.v1.CheckStockResponse;
import com.observability.lab.inventory.grpc.v1.InventoryServiceGrpc;
import com.observability.lab.inventory.grpc.v1.ReservationLine;
import com.observability.lab.inventory.grpc.v1.ReserveStockRequest;
import com.observability.lab.inventory.grpc.v1.ReserveStockResponse;
import com.observability.lab.order.application.AvailabilityView;
import com.observability.lab.order.application.CreateOrderCommand;
import com.observability.lab.shared.tracing.Spans;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Order Service's synchronous view of inventory, over gRPC.
 *
 * <p>Every call here sets a deadline, is wrapped in the circuit breaker, and has a defined fallback.
 * The three go together: <strong>a circuit breaker with no fallback is just a faster failure</strong>,
 * and a deadline with no breaker keeps a struggling dependency under load until every caller has
 * timed out individually.
 *
 * <p>The fallbacks differ by operation because the honest answer differs:
 *
 * <ul>
 *   <li>An availability check degrades to "unknown" for every line. The caller shows "availability
 *       unavailable" rather than a confident and wrong "out of stock".
 *   <li>An express reservation falls through to the Kafka path. Nothing is lost — that path was
 *       always the authoritative one, and the customer simply waits a moment longer. This fallback
 *       is the asynchronous design paying off: the breaker has somewhere safe to land precisely
 *       because the asynchronous path is the invariant rather than a bolt-on.
 * </ul>
 *
 * <p>Registered as a bean by {@link InventoryChannelConfiguration} rather than component-scanned, so
 * that turning the gRPC hop off removes the client along with the channel it would have used.
 */
public class InventoryGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryGrpcClient.class);

    private final InventoryServiceGrpc.InventoryServiceBlockingStub stub;
    private final CircuitBreaker breaker;
    private final InventoryGrpcProperties properties;

    public InventoryGrpcClient(InventoryServiceGrpc.InventoryServiceBlockingStub stub,
            CircuitBreaker inventoryCircuitBreaker, InventoryGrpcProperties properties) {
        this.stub = stub;
        this.breaker = inventoryCircuitBreaker;
        this.properties = properties;
    }

    /**
     * Availability for a whole basket in one round trip.
     *
     * <p>The N+1 fix, and the reason the contract exists. The REST equivalent costs one HTTP request
     * per SKU, which is linear in basket size on the checkout path; this is one request and one
     * {@code IN} query regardless of how many lines the basket has.
     */
    public List<AvailabilityView> batchCheck(List<CreateOrderCommand.Line> lines) {
        BatchCheckStockRequest request = BatchCheckStockRequest.newBuilder()
                .addAllItems(lines.stream()
                        .map(line -> CheckStockRequest.newBuilder()
                                .setProductSku(line.productSku())
                                .setRequestedQuantity(line.quantity())
                                .build())
                        .toList())
                .build();

        Spans.attribute(Spans.LINE_COUNT, lines.size());

        return guarded(
                () -> toViews(lines, withDeadline(properties.batchCheckDeadline()).batchCheckStock(request)),
                () -> degraded(lines),
                "BatchCheckStock");
    }

    /** Availability for one product. Kept because a single-line check should not pay batch overhead. */
    public AvailabilityView check(String productSku, int quantity) {
        CheckStockRequest request = CheckStockRequest.newBuilder()
                .setProductSku(productSku)
                .setRequestedQuantity(quantity)
                .build();

        return guarded(
                () -> toView(withDeadline(properties.checkStockDeadline()).checkStock(request), quantity),
                () -> AvailabilityView.unknown(productSku, quantity),
                "CheckStock");
    }

    /**
     * Tries to reserve stock synchronously, and shrugs if it cannot.
     *
     * <p>The deadline is deliberately tighter than the caller's own budget, so a slow Inventory
     * Service surfaces as a fast {@code PENDING} rather than as a slow request. Missing it costs
     * nothing: the {@code order-created} event is already in the outbox and will reserve the same
     * stock under the same {@code event_id}, so this attempt is a latency optimisation that cannot
     * change the outcome.
     *
     * @param eventId the identifier the Kafka path will use for the same reservation. Sharing it is
     *                what makes the two paths safe to race — whichever arrives second is a no-op
     *                that replays the recorded decision
     */
    public ExpressReservation reserve(String eventId, String orderNumber,
            List<CreateOrderCommand.Line> lines) {

        ReserveStockRequest request = ReserveStockRequest.newBuilder()
                .setEventId(eventId)
                .setOrderNumber(orderNumber)
                .addAllLines(lines.stream()
                        .map(line -> ReservationLine.newBuilder()
                                .setProductSku(line.productSku())
                                .setQuantity(line.quantity())
                                .build())
                        .toList())
                .build();

        return guarded(
                () -> toReservation(
                        withDeadline(properties.reserveStockDeadline()).reserveStock(request)),
                ExpressReservation::unavailable,
                "ReserveStock");
    }

    // --- Plumbing ------------------------------------------------------------

    /**
     * Runs a call through the breaker, falling back rather than propagating.
     *
     * <p>Failures are recorded on the breaker; refusals are not. That distinction is enforced by the
     * breaker's own {@code recordException} predicate rather than here, so there is exactly one
     * definition of "the dependency is unwell" and it lives with the configuration that acts on it.
     */
    private <T> T guarded(Supplier<T> call, Supplier<T> fallback, String method) {
        try {
            return breaker.executeSupplier(call);

        } catch (CallNotPermittedException open) {
            // The circuit is open: the Inventory Service is not contacted at all. Recorded as a span
            // event so the trace shows why a call that normally takes 15ms took none.
            Spans.event("inventory.circuit.open");
            log.warn("Circuit for the inventory gRPC hop is open; skipping {}", method);
            return fallback.get();

        } catch (StatusRuntimeException failure) {
            // Not marked as a span error: a dependency being unavailable is not this service
            // failing, and putting it in the error rate would make our alerts track their uptime.
            Spans.attribute(Spans.OUTCOME, "inventory_" + failure.getStatus().getCode().name().toLowerCase(
                    java.util.Locale.ROOT));
            log.warn("{} failed with {}; falling back", method, failure.getStatus().getCode());
            return fallback.get();
        }
    }

    /**
     * Applies the operation's deadline to the stub.
     *
     * <p>{@code withDeadlineAfter} returns a new stub rather than mutating the shared one — stubs
     * are immutable, and a deadline set once at construction would be an absolute point in time that
     * every call after the first would find already expired.
     */
    private InventoryServiceGrpc.InventoryServiceBlockingStub withDeadline(java.time.Duration budget) {
        return stub.withDeadlineAfter(budget.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static List<AvailabilityView> toViews(List<CreateOrderCommand.Line> lines,
            BatchCheckStockResponse response) {
        // Positional, which the contract guarantees: a SKU inventory does not track comes back with
        // tracked = false rather than being omitted, precisely so the lists can be zipped.
        return java.util.stream.IntStream.range(0, lines.size())
                .mapToObj(i -> toView(response.getResults(i), lines.get(i).quantity()))
                .toList();
    }

    private static AvailabilityView toView(CheckStockResponse result, int requested) {
        return result.getTracked()
                ? AvailabilityView.of(result.getProductSku(), requested, result.getAvailableQuantity())
                : AvailabilityView.unknown(result.getProductSku(), requested);
    }

    /** Every line reported as unknown, so the UI says "cannot tell" rather than "out of stock". */
    private static List<AvailabilityView> degraded(List<CreateOrderCommand.Line> lines) {
        return lines.stream()
                .map(line -> AvailabilityView.unknown(line.productSku(), line.quantity()))
                .toList();
    }

    private static ExpressReservation toReservation(ReserveStockResponse response) {
        return switch (response.getOutcome()) {
            case RESERVATION_OUTCOME_RESERVED -> ExpressReservation.reserved(response.getReplayed());
            case RESERVATION_OUTCOME_REJECTED ->
                    ExpressReservation.rejected(response.getShortagesList(), response.getReplayed());
            // UNSPECIFIED, UNRECOGNIZED, and anything a future server adds. A switch without this
            // arm compiles cleanly and fails the first time the server is upgraded - which is the
            // whole reason the contract insists every enum has a zero value meaning "unspecified".
            default -> ExpressReservation.unavailable();
        };
    }
}
