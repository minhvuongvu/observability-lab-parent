package com.observability.lab.inventory.grpc;

import com.google.protobuf.Timestamp;
import com.observability.lab.inventory.application.BulkAdjustmentResult;
import com.observability.lab.inventory.application.ReservationResult;
import com.observability.lab.inventory.application.StockApplicationService;
import com.observability.lab.inventory.application.StockAvailability;
import com.observability.lab.inventory.application.StockChangedEvent;
import com.observability.lab.inventory.grpc.v1.BatchCheckStockRequest;
import com.observability.lab.inventory.grpc.v1.BatchCheckStockResponse;
import com.observability.lab.inventory.grpc.v1.BulkAdjustStockSummary;
import com.observability.lab.inventory.grpc.v1.CheckStockRequest;
import com.observability.lab.inventory.grpc.v1.CheckStockResponse;
import com.observability.lab.inventory.grpc.v1.InventoryServiceGrpc;
import com.observability.lab.inventory.grpc.v1.ReservationOutcome;
import com.observability.lab.inventory.grpc.v1.ReserveStockRequest;
import com.observability.lab.inventory.grpc.v1.ReserveStockResponse;
import com.observability.lab.inventory.grpc.v1.StockAdjustment;
import com.observability.lab.inventory.grpc.v1.StockChangeReason;
import com.observability.lab.inventory.grpc.v1.StockUpdate;
import com.observability.lab.inventory.grpc.v1.WatchStockLevelsRequest;
import com.observability.lab.shared.api.FieldViolation;
import com.observability.lab.shared.exception.ForbiddenException;
import com.observability.lab.shared.exception.ValidationException;
import com.observability.lab.shared.grpc.GrpcCallContext;
import com.observability.lab.shared.tracing.Spans;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The gRPC face of the Inventory Service.
 *
 * <p>A second transport over the same application service, never a second implementation of it. The
 * REST controller and this class both translate and delegate; every rule about stock lives in
 * {@link StockApplicationService} and the domain, so the two transports cannot disagree about what
 * the service does.
 *
 * <p>Three things this class is responsible for and the REST controller is not:
 *
 * <ul>
 *   <li><strong>Deadline awareness.</strong> The caller's remaining budget arrives with the call, so
 *       work that cannot finish inside it is declined rather than started.
 *   <li><strong>Stream lifecycle.</strong> A subscription is a held resource and has to be released
 *       on cancellation, not only on completion.
 *   <li><strong>Back-pressure.</strong> Client streaming means the flush cadence is this class's
 *       decision, and HTTP/2 flow control does the rest.
 * </ul>
 *
 * <p>No status is constructed here. Handlers throw the platform's domain exceptions and
 * {@link com.observability.lab.shared.grpc.GrpcStatusMapper} decides the status — one mapping in one
 * place, or the taxonomy drifts per method and the retry policy stops meaning anything.
 */
@Component
public class InventoryGrpcService extends InventoryServiceGrpc.InventoryServiceImplBase {

    /**
     * Largest batch accepted.
     *
     * <p>Rejected with INVALID_ARGUMENT rather than served slowly. An unbounded batch is a
     * denial-of-service primitive: one request, one enormous {@code IN} predicate, one Oracle
     * connection held for the duration.
     */
    static final int MAX_BATCH_SIZE = 100;

    /**
     * Least remaining budget worth starting database work for.
     *
     * <p>Under load this is the difference between shedding load and spending a connection on a
     * result nobody will receive — which is how a slow service becomes an unavailable one.
     */
    private static final Duration MINIMUM_BUDGET = Duration.ofMillis(50);

    /** Applied every this many adjustments, so row locks stay short and progress is visible. */
    private static final int BULK_FLUSH_SIZE = 500;

    /** Only an operator may rewrite stock levels wholesale. */
    private static final String ADMIN = "ROLE_ADMIN";

    private static final Logger log = LoggerFactory.getLogger(InventoryGrpcService.class);

    private final StockApplicationService stock;
    private final StockWatchRegistry watches;

    public InventoryGrpcService(StockApplicationService stock, StockWatchRegistry watches) {
        this.stock = stock;
        this.watches = watches;
    }

    // --- Unary ---------------------------------------------------------------

    @Override
    public void checkStock(CheckStockRequest request, StreamObserver<CheckStockResponse> observer) {
        requireSku(request.getProductSku(), "product_sku");
        requireBudget();

        StockAvailability availability =
                stock.availabilityFor(List.of(request.getProductSku())).getFirst();

        Spans.attribute(Spans.OUTCOME, availability.tracked() ? "tracked" : "untracked");

        observer.onNext(toResponse(availability, request.getRequestedQuantity()));
        observer.onCompleted();
    }

    @Override
    public void batchCheckStock(BatchCheckStockRequest request,
            StreamObserver<BatchCheckStockResponse> observer) {

        List<CheckStockRequest> items = request.getItemsList();
        validateBatch(items);
        requireBudget();

        List<StockAvailability> answers =
                stock.availabilityFor(items.stream().map(CheckStockRequest::getProductSku).toList());

        BatchCheckStockResponse.Builder response = BatchCheckStockResponse.newBuilder();
        int sufficient = 0;
        for (int i = 0; i < answers.size(); i++) {
            int requested = items.get(i).getRequestedQuantity();
            if (answers.get(i).sufficientFor(requested)) {
                sufficient++;
            }
            response.addResults(toResponse(answers.get(i), requested));
        }

        Spans.attribute(Spans.LINE_COUNT, items.size());

        // Shape, not content. Logging the request would put the customer's basket into a log store,
        // and a log store is not an access-controlled datastore. Counts and outcomes are what an
        // investigation actually needs; the basket is in the order, behind proper access control.
        log.info("BatchCheckStock handled {} sku(s): {} sufficient, {} short",
                items.size(), sufficient, items.size() - sufficient);

        observer.onNext(response.build());
        observer.onCompleted();
    }

    @Override
    public void reserveStock(ReserveStockRequest request,
            StreamObserver<ReserveStockResponse> observer) {

        List<FieldViolation> violations = new ArrayList<>();
        if (request.getEventId().isBlank()) {
            violations.add(new FieldViolation("event_id", "must not be blank"));
        }
        if (request.getOrderNumber().isBlank()) {
            violations.add(new FieldViolation("order_number", "must not be blank"));
        }
        if (request.getLinesCount() == 0) {
            violations.add(new FieldViolation("lines", "at least one line is required"));
        }
        for (int i = 0; i < request.getLinesCount(); i++) {
            var line = request.getLines(i);
            if (line.getProductSku().isBlank()) {
                violations.add(new FieldViolation("lines[" + i + "].product_sku", "must not be blank"));
            }
            if (line.getQuantity() <= 0) {
                violations.add(new FieldViolation("lines[" + i + "].quantity", "must be positive"));
            }
        }
        reject(violations);
        requireBudget();

        // The same use case the Kafka listener drives, keyed by the same event_id. That shared key
        // is the entire reason the synchronous and asynchronous paths are safe to race: whichever
        // arrives second finds the event already applied and replays the recorded decision instead
        // of reserving twice.
        ReservationResult result = stock.reserveForOrder(
                request.getEventId(),
                request.getOrderNumber(),
                request.getLinesList().stream()
                        .map(line -> new com.observability.lab.inventory.application.ReservationLine(
                                line.getProductSku(), line.getQuantity()))
                        .toList());

        observer.onNext(ReserveStockResponse.newBuilder()
                .setOutcome(toOutcome(result.settlement()))
                .addAllShortages(result.shortages())
                .setReplayed(result.outcome() == ReservationResult.Outcome.ALREADY_PROCESSED)
                .build());
        observer.onCompleted();
    }

    // --- Server streaming ----------------------------------------------------

    @Override
    public void watchStockLevels(WatchStockLevelsRequest request,
            StreamObserver<StockUpdate> observer) {

        Set<String> skus = new LinkedHashSet<>(request.getProductSkusList());
        if (skus.isEmpty()) {
            throw new ValidationException("A watchlist must name at least one product.",
                    List.of(new FieldViolation("product_skus", "must not be empty")));
        }

        ServerCallStreamObserver<StockUpdate> stream = (ServerCallStreamObserver<StockUpdate>) observer;

        // A subscription is a held resource. Registering before the initial read means a change
        // committed *during* that read is delivered rather than lost in the gap between the two -
        // the client may see it twice, which is harmless for an absolute level, where missing it
        // entirely is not.
        AutoCloseable subscription = watches.subscribe(skus, event -> emit(stream, event));

        // Both callbacks, not just one. onCancelHandler fires when the client goes away, which is
        // how a long-lived stream normally ends; without it the subscription outlives the stream and
        // accumulates silently until the process is restarted.
        stream.setOnCancelHandler(() -> close(subscription, "cancelled"));
        stream.setOnCloseHandler(() -> close(subscription, "closed"));

        if (request.getSendInitialState()) {
            // Without this a client cannot tell "no change yet" from "not subscribed", and the two
            // call for very different responses.
            Instant asOf = Instant.now();
            stock.availabilityFor(List.copyOf(skus)).stream()
                    .filter(StockAvailability::tracked)
                    .forEach(availability -> emit(stream, new StockChangedEvent(
                            availability.productSku(), availability.availableQuantity(),
                            availability.reservedQuantity(), asOf, null)));
        }

        // Deliberately no onCompleted(). The stream stays open until the client cancels or the
        // server shuts down; completing here would turn a subscription into a one-shot read.
        log.info("Stock watch opened for {} sku(s)", skus.size());
    }

    // --- Client streaming ----------------------------------------------------

    @Override
    public StreamObserver<StockAdjustment> bulkAdjustStock(
            StreamObserver<BulkAdjustStockSummary> observer) {

        // Checked when the stream opens rather than at the first message: refusing after the client
        // has already streamed ten thousand records wastes both sides' work.
        if (!GrpcCallContext.hasRole(ADMIN)) {
            throw new ForbiddenException("Bulk stock adjustment requires the ADMIN role.");
        }

        return new StreamObserver<>() {

            private final List<com.observability.lab.inventory.application.StockAdjustment> buffer =
                    new ArrayList<>(BULK_FLUSH_SIZE);
            private final AtomicInteger received = new AtomicInteger();
            private BulkAdjustmentResult total = BulkAdjustmentResult.empty();
            private final long startedAt = System.nanoTime();

            @Override
            public void onNext(StockAdjustment adjustment) {
                received.incrementAndGet();
                buffer.add(new com.observability.lab.inventory.application.StockAdjustment(
                        adjustment.getProductSku(),
                        adjustment.getQuantityDelta(),
                        adjustment.getReference()));

                if (buffer.size() >= BULK_FLUSH_SIZE) {
                    flush();
                }
            }

            @Override
            public void onError(Throwable failure) {
                // The client went away mid-stream. Whatever was flushed stands - each batch is its
                // own transaction, by design - and the buffer is discarded because nothing is
                // waiting for the summary.
                log.warn("Bulk adjustment stream failed after {} record(s): {}",
                        received.get(), failure.toString());
            }

            @Override
            public void onCompleted() {
                flush();
                long durationMs = java.util.concurrent.TimeUnit.NANOSECONDS
                        .toMillis(System.nanoTime() - startedAt);

                log.info("Bulk adjustment applied {} of {} record(s) in {}ms",
                        total.applied(), received.get(), durationMs);

                observer.onNext(BulkAdjustStockSummary.newBuilder()
                        .setApplied(total.applied())
                        .setRejected(total.rejected())
                        .addAllRejectionReasons(total.rejections())
                        .setDurationMs(durationMs)
                        .build());
                observer.onCompleted();
            }

            /**
             * Applies the buffer in one transaction and clears it.
             *
             * <p>Flushing on a size threshold rather than at the end is what makes HTTP/2 flow
             * control useful here: a slow Oracle slows this handler, which stops reading, which
             * back-pressures the producer. The REST equivalent - a single request carrying a
             * ten-thousand-element JSON array - has no such valve and fills a buffer until the
             * process dies.
             */
            private void flush() {
                if (buffer.isEmpty()) {
                    return;
                }
                total = total.plus(stock.applyAdjustments(List.copyOf(buffer)));
                buffer.clear();
            }
        };
    }

    // --- Translation ---------------------------------------------------------

    private static CheckStockResponse toResponse(StockAvailability availability, int requested) {
        return CheckStockResponse.newBuilder()
                .setProductSku(availability.productSku())
                .setAvailableQuantity(availability.availableQuantity())
                .setReservedQuantity(availability.reservedQuantity())
                .setSufficient(availability.sufficientFor(requested))
                .setTracked(availability.tracked())
                .setAsOf(toTimestamp(availability.asOf()))
                .build();
    }

    private static void emit(ServerCallStreamObserver<StockUpdate> stream, StockChangedEvent event) {
        if (stream.isCancelled()) {
            // Writing to a cancelled stream throws, and the registry would then drop the
            // subscription on a failure rather than on the cancellation that actually happened.
            return;
        }
        stream.onNext(StockUpdate.newBuilder()
                .setProductSku(event.productSku())
                .setAvailableQuantity(event.availableQuantity())
                .setReservedQuantity(event.reservedQuantity())
                .setOccurredAt(toTimestamp(event.occurredAt()))
                .setReason(toReason(event.reason()))
                .build());
    }

    private static StockChangeReason toReason(StockChangedEvent.Reason reason) {
        if (reason == null) {
            return StockChangeReason.STOCK_CHANGE_REASON_INITIAL_STATE;
        }
        return switch (reason) {
            case RESERVED -> StockChangeReason.STOCK_CHANGE_REASON_RESERVED;
            case RELEASED -> StockChangeReason.STOCK_CHANGE_REASON_RELEASED;
            case RECEIVED -> StockChangeReason.STOCK_CHANGE_REASON_RECEIVED;
            case ADJUSTED -> StockChangeReason.STOCK_CHANGE_REASON_ADJUSTED;
        };
    }

    private static ReservationOutcome toOutcome(ReservationResult.Outcome settlement) {
        if (settlement == null) {
            // An old row with no recorded decision. UNSPECIFIED is the honest answer, and the
            // contract documents that a caller must treat it as unknown rather than as success.
            return ReservationOutcome.RESERVATION_OUTCOME_UNSPECIFIED;
        }
        return switch (settlement) {
            case RESERVED -> ReservationOutcome.RESERVATION_OUTCOME_RESERVED;
            case REJECTED -> ReservationOutcome.RESERVATION_OUTCOME_REJECTED;
            case ALREADY_PROCESSED -> ReservationOutcome.RESERVATION_OUTCOME_UNSPECIFIED;
        };
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    // --- Guards --------------------------------------------------------------

    private static void validateBatch(List<CheckStockRequest> items) {
        List<FieldViolation> violations = new ArrayList<>();
        if (items.isEmpty()) {
            violations.add(new FieldViolation("items", "at least one item is required"));
        }
        if (items.size() > MAX_BATCH_SIZE) {
            violations.add(new FieldViolation("items", "at most " + MAX_BATCH_SIZE + " items"));
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getProductSku().isBlank()) {
                violations.add(new FieldViolation("items[" + i + "].product_sku", "must not be blank"));
            }
        }
        reject(violations);
    }

    private static void requireSku(String productSku, String field) {
        if (productSku == null || productSku.isBlank()) {
            reject(List.of(new FieldViolation(field, "must not be blank")));
        }
    }

    /**
     * Refuses to start work the caller has effectively already abandoned.
     *
     * <p>DEADLINE_EXCEEDED rather than a best-effort attempt: the caller will discard the answer
     * either way, and the difference is whether this service spent an Oracle connection producing it.
     */
    private static void requireBudget() {
        if (GrpcCallContext.budgetExhausted(MINIMUM_BUDGET)) {
            throw Status.DEADLINE_EXCEEDED
                    .withDescription("insufficient remaining deadline")
                    .asRuntimeException();
        }
    }

    private static void reject(List<FieldViolation> violations) {
        if (!violations.isEmpty()) {
            throw new ValidationException("request validation failed", violations);
        }
    }

    private static void close(AutoCloseable subscription, String why) {
        try {
            subscription.close();
        } catch (Exception ignored) {
            // The handle only removes a set entry; it cannot fail in practice, and a failure to
            // tidy up must never become the status a client sees.
        }
        log.debug("Stock watch {}", why);
    }
}
