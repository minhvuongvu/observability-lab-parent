package com.observability.lab.inventory.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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
import com.observability.lab.inventory.grpc.v1.ReservationLine;
import com.observability.lab.inventory.grpc.v1.ReservationOutcome;
import com.observability.lab.inventory.grpc.v1.ReserveStockRequest;
import com.observability.lab.inventory.grpc.v1.ReserveStockResponse;
import com.observability.lab.inventory.grpc.v1.StockAdjustment;
import com.observability.lab.inventory.grpc.v1.StockChangeReason;
import com.observability.lab.inventory.grpc.v1.StockUpdate;
import com.observability.lab.inventory.grpc.v1.WatchStockLevelsRequest;
import com.observability.lab.shared.correlation.ServiceIdentity;
import com.observability.lab.shared.grpc.GrpcCallContext;
import com.observability.lab.shared.grpc.GrpcCorrelationServerInterceptor;
import com.observability.lab.shared.grpc.GrpcExceptionServerInterceptor;
import com.observability.lab.shared.grpc.GrpcLoggingServerInterceptor;
import com.observability.lab.shared.grpc.GrpcMetadataKeys;
import com.observability.lab.shared.grpc.GrpcMetricsServerInterceptor;
import com.observability.lab.shared.grpc.GrpcServerInterceptorChain;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Drives the real service through the real interceptor chain over an in-process transport.
 *
 * <p>Not a mock of the stub. The point is precisely the part a direct method call would skip: the
 * exception interceptor turning a domain exception into a status, the correlation interceptor
 * reading and sanitising metadata, the generated marshalling, and the stream lifecycle. A test that
 * calls the handler directly proves the handler works and says nothing about the four things most
 * likely to be wrong.
 *
 * <p>Token verification is not in this chain: it has its own decoder and belongs in its own test.
 * The roles it would publish are supplied by {@link RoleStub}, so the {@code ADMIN} rule on
 * {@code BulkAdjustStock} is exercised without turning every test here into a JWT test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InventoryService over gRPC")
class InventoryGrpcServiceTest {

    /** Test-only metadata key standing in for a verified token's realm roles. */
    private static final Metadata.Key<String> ROLES_KEY =
            Metadata.Key.of("x-test-roles", Metadata.ASCII_STRING_MARSHALLER);

    @Mock
    private StockApplicationService stock;

    private StockWatchRegistry watches;
    private Server server;
    private ManagedChannel channel;
    private InventoryServiceGrpc.InventoryServiceBlockingStub client;
    private InventoryServiceGrpc.InventoryServiceStub asyncClient;
    private ContextProbe probe;

    @BeforeEach
    void startServer() throws Exception {
        watches = new StockWatchRegistry();
        probe = new ContextProbe();

        List<ServerInterceptor> chain = List.of(
                new GrpcCorrelationServerInterceptor(
                        new ServiceIdentity("inventory-service", "test", "test")),
                new GrpcMetricsServerInterceptor(new SimpleMeterRegistry()),
                new GrpcLoggingServerInterceptor(),
                new RoleStub(),
                probe,
                new GrpcExceptionServerInterceptor());

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                // Everything runs inline on the calling thread, so a handler has finished by the
                // time the stub call returns and the tests need no sleeps to be deterministic.
                .directExecutor()
                .addService(new GrpcServerInterceptorChain(chain)
                        .wrap(new InventoryGrpcService(stock, watches)))
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = InventoryServiceGrpc.newBlockingStub(channel);
        asyncClient = InventoryServiceGrpc.newStub(channel);
    }

    @AfterEach
    void stopServer() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Nested
    @DisplayName("CheckStock")
    class CheckStock {

        @Test
        @DisplayName("reports availability and whether it satisfies the request")
        void answersATrackedSku() {
            when(stock.availabilityFor(List.of("SKU-A")))
                    .thenReturn(List.of(availability("SKU-A", 10, 2, true)));

            CheckStockResponse response = client.checkStock(CheckStockRequest.newBuilder()
                    .setProductSku("SKU-A").setRequestedQuantity(3).build());

            assertThat(response.getAvailableQuantity()).isEqualTo(10);
            assertThat(response.getReservedQuantity()).isEqualTo(2);
            assertThat(response.getTracked()).isTrue();
            assertThat(response.getSufficient()).isTrue();
            // Lets a caller reason about staleness rather than assume the answer is current.
            assertThat(response.hasAsOf()).isTrue();
        }

        @Test
        @DisplayName("an untracked SKU is tracked=false, not an error and not merely zero-available")
        void distinguishesUntrackedFromEmpty() {
            // available_quantity == 0 alone would be ambiguous, and "none left" versus "never heard
            // of it" call for completely different responses from the caller.
            when(stock.availabilityFor(List.of("SKU-NOPE")))
                    .thenReturn(List.of(availability("SKU-NOPE", 0, 0, false)));

            CheckStockResponse response = client.checkStock(CheckStockRequest.newBuilder()
                    .setProductSku("SKU-NOPE").setRequestedQuantity(1).build());

            assertThat(response.getTracked()).isFalse();
            assertThat(response.getSufficient()).isFalse();
        }

        @Test
        @DisplayName("a blank sku is INVALID_ARGUMENT, whatever the database contains")
        void rejectsABlankSku() {
            assertThat(codeOf(() -> client.checkStock(CheckStockRequest.getDefaultInstance())))
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }
    }

    @Nested
    @DisplayName("BatchCheckStock")
    class BatchCheck {

        @Test
        @DisplayName("answers every line positionally, in request order")
        void answersInOrder() {
            when(stock.availabilityFor(List.of("SKU-A", "SKU-B", "SKU-C"))).thenReturn(List.of(
                    availability("SKU-A", 10, 0, true),
                    availability("SKU-B", 0, 0, false),
                    availability("SKU-C", 1, 0, true)));

            BatchCheckStockResponse response = client.batchCheckStock(
                    BatchCheckStockRequest.newBuilder()
                            .addItems(item("SKU-A", 5))
                            .addItems(item("SKU-B", 5))
                            .addItems(item("SKU-C", 5))
                            .build());

            assertThat(response.getResultsCount()).isEqualTo(3);
            assertThat(response.getResults(0).getProductSku()).isEqualTo("SKU-A");
            assertThat(response.getResults(0).getSufficient()).isTrue();
            // Present rather than omitted, which is what lets the caller zip the lists.
            assertThat(response.getResults(1).getTracked()).isFalse();
            assertThat(response.getResults(2).getSufficient()).isFalse();
        }

        @Test
        @DisplayName("a batch over the ceiling is refused rather than served slowly")
        void boundsTheBatch() {
            // An unbounded batch is a denial-of-service primitive: one request, one enormous IN
            // predicate, one database connection held for its duration.
            BatchCheckStockRequest.Builder request = BatchCheckStockRequest.newBuilder();
            for (int i = 0; i <= InventoryGrpcService.MAX_BATCH_SIZE; i++) {
                request.addItems(item("SKU-" + i, 1));
            }

            assertThat(codeOf(() -> client.batchCheckStock(request.build())))
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        @DisplayName("an empty batch is INVALID_ARGUMENT")
        void rejectsAnEmptyBatch() {
            assertThat(codeOf(() ->
                    client.batchCheckStock(BatchCheckStockRequest.getDefaultInstance())))
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }
    }

    @Nested
    @DisplayName("ReserveStock")
    class Reserve {

        @Test
        @DisplayName("a successful reservation reports RESERVED")
        void reserves() {
            when(stock.reserveForOrder(anyString(), anyString(), anyList())).thenReturn(reserved());

            ReserveStockResponse response = client.reserveStock(reservationRequest());

            assertThat(response.getOutcome())
                    .isEqualTo(ReservationOutcome.RESERVATION_OUTCOME_RESERVED);
            assertThat(response.getReplayed()).isFalse();
        }

        @Test
        @DisplayName("a shortage is an answer carrying shortages, not an error status")
        void refusalIsAnAnswer() {
            // A rejected reservation is this service working correctly. An error status would put
            // ordinary operation into the fault rate every alert is built on.
            when(stock.reserveForOrder(anyString(), anyString(), anyList()))
                    .thenReturn(new ReservationResult(ReservationResult.Outcome.REJECTED,
                            List.of("SKU-A (requested 5, available 1)"),
                            ReservationResult.Outcome.REJECTED));

            ReserveStockResponse response = client.reserveStock(reservationRequest());

            assertThat(response.getOutcome())
                    .isEqualTo(ReservationOutcome.RESERVATION_OUTCOME_REJECTED);
            assertThat(response.getShortagesList()).hasSize(1);
        }

        @Test
        @DisplayName("a redelivery replays the recorded decision and says so")
        void replaysADuplicate() {
            // The property that lets the gRPC and Kafka paths race: the same event_id always yields
            // the same outcome, whichever arrives second.
            when(stock.reserveForOrder(anyString(), anyString(), anyList()))
                    .thenReturn(new ReservationResult(ReservationResult.Outcome.ALREADY_PROCESSED,
                            List.of(), ReservationResult.Outcome.RESERVED));

            ReserveStockResponse response = client.reserveStock(reservationRequest());

            assertThat(response.getOutcome())
                    .isEqualTo(ReservationOutcome.RESERVATION_OUTCOME_RESERVED);
            assertThat(response.getReplayed()).isTrue();
        }

        @Test
        @DisplayName("a missing event id is INVALID_ARGUMENT with structured detail, not prose")
        void requiresAnEventId() {
            StatusRuntimeException failure = failureOf(() -> client.reserveStock(
                    ReserveStockRequest.newBuilder().setOrderNumber("ORD-1").build()));

            assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            // google.rpc.BadRequest in the trailers, so a client branches on a field path rather
            // than parsing an English message.
            assertThat(failure.getTrailers().keys()).contains("google.rpc.status-bin");
        }
    }

    @Nested
    @DisplayName("WatchStockLevels")
    class Watch {

        @Test
        @DisplayName("sends current levels on subscribe when asked")
        void sendsInitialState() throws Exception {
            when(stock.availabilityFor(anyList()))
                    .thenReturn(List.of(availability("SKU-A", 7, 1, true)));

            List<StockUpdate> received = new ArrayList<>();
            CountDownLatch first = new CountDownLatch(1);

            asyncClient.watchStockLevels(
                    WatchStockLevelsRequest.newBuilder()
                            .addProductSkus("SKU-A").setSendInitialState(true).build(),
                    collector(received, first));

            assertThat(first.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received).hasSize(1);
            assertThat(received.getFirst().getAvailableQuantity()).isEqualTo(7);
            // Without a distinct reason a client cannot tell the opening snapshot from a change.
            assertThat(received.getFirst().getReason())
                    .isEqualTo(StockChangeReason.STOCK_CHANGE_REASON_INITIAL_STATE);
        }

        @Test
        @DisplayName("streams a watched change and ignores one the client did not ask for")
        void streamsOnChange() throws Exception {
            when(stock.availabilityFor(anyList())).thenReturn(List.of());

            List<StockUpdate> received = new ArrayList<>();
            CountDownLatch delivered = new CountDownLatch(1);

            asyncClient.watchStockLevels(
                    WatchStockLevelsRequest.newBuilder().addProductSkus("SKU-A").build(),
                    collector(received, delivered));

            watches.onStockChanged(change("SKU-B", StockChangedEvent.Reason.RECEIVED));
            watches.onStockChanged(change("SKU-A", StockChangedEvent.Reason.RESERVED));

            assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received).hasSize(1);
            assertThat(received.getFirst().getProductSku()).isEqualTo("SKU-A");
            assertThat(received.getFirst().getReason())
                    .isEqualTo(StockChangeReason.STOCK_CHANGE_REASON_RESERVED);
        }

        @Test
        @DisplayName("cancelling releases the subscription rather than leaking it")
        void releasesOnCancel() {
            when(stock.availabilityFor(anyList())).thenReturn(List.of());

            asyncClient.watchStockLevels(
                    WatchStockLevelsRequest.newBuilder().addProductSkus("SKU-A").build(),
                    collector(new ArrayList<>(), new CountDownLatch(1)));

            assertThat(watches.activeSubscriptions()).isEqualTo(1);

            // A client that goes away is the normal way a long-lived stream ends. Without the
            // cancel handler the subscription outlives the stream and accumulates silently.
            channel.shutdownNow();
            await(() -> watches.activeSubscriptions() == 0);
            assertThat(watches.activeSubscriptions()).isZero();
        }

        @Test
        @DisplayName("an empty watchlist is INVALID_ARGUMENT")
        void rejectsAnEmptyWatchlist() {
            assertThat(codeOf(() -> client.watchStockLevels(
                    WatchStockLevelsRequest.getDefaultInstance()).next()))
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }
    }

    @Nested
    @DisplayName("BulkAdjustStock")
    class BulkAdjust {

        @Test
        @DisplayName("summarises the job once the client half-closes")
        void appliesAndSummarises() throws Exception {
            when(stock.applyAdjustments(anyList()))
                    .thenReturn(new BulkAdjustmentResult(2, List.of("SKU-X (not tracked)")));

            Uploaded uploaded = new Uploaded();
            StreamObserver<StockAdjustment> upload =
                    asyncAsAdmin().bulkAdjustStock(uploaded.observer());

            upload.onNext(adjustment("SKU-A", 5));
            upload.onNext(adjustment("SKU-B", -2));
            upload.onNext(adjustment("SKU-X", 1));
            upload.onCompleted();

            assertThat(uploaded.done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(uploaded.failure.get()).isNull();
            assertThat(uploaded.summary.get().getApplied()).isEqualTo(2);
            assertThat(uploaded.summary.get().getRejected()).isEqualTo(1);
            assertThat(uploaded.summary.get().getRejectionReasonsList())
                    .containsExactly("SKU-X (not tracked)");
        }

        @Test
        @DisplayName("a caller without ADMIN is refused as the stream opens, not after it uploads")
        void requiresAdmin() throws Exception {
            // Refusing only after ten thousand records have been streamed wastes both sides' work.
            Uploaded uploaded = new Uploaded();
            asyncClient.bulkAdjustStock(uploaded.observer());

            assertThat(uploaded.done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(Status.fromThrowable(uploaded.failure.get()).getCode())
                    .isEqualTo(Status.Code.PERMISSION_DENIED);
        }
    }

    @Nested
    @DisplayName("metadata propagation")
    class MetadataPropagation {

        @Test
        @DisplayName("the caller's correlation id and service name reach the handler's context")
        void carriesIdentityAcrossTheHop() {
            when(stock.availabilityFor(anyList()))
                    .thenReturn(List.of(availability("SKU-A", 1, 0, true)));

            Metadata metadata = new Metadata();
            metadata.put(GrpcMetadataKeys.CORRELATION_ID, "ORD-20260721-42BDB094");
            metadata.put(GrpcMetadataKeys.CALLER_SERVICE, "order-service");

            checkWith(metadata);

            assertThat(probe.correlationId.get()).isEqualTo("ORD-20260721-42BDB094");
            // Not inferable from the network path once a channel is long-lived and client-balanced,
            // which is exactly why the caller states it.
            assertThat(probe.callerService.get()).isEqualTo("order-service");
        }

        @Test
        @DisplayName("a call with no correlation id is given one rather than left unattributed")
        void generatesWhatIsMissing() {
            when(stock.availabilityFor(anyList()))
                    .thenReturn(List.of(availability("SKU-A", 1, 0, true)));

            checkWith(new Metadata());

            assertThat(probe.correlationId.get()).isNotBlank();
            assertThat(probe.requestId.get()).isNotBlank();
        }

        @Test
        @DisplayName("a forged correlation id is discarded instead of written into the logs")
        void rejectsHostileIdentifiers() {
            // A quote or a brace in a JSON pipeline breaks the record the parser is building, and a
            // newline lets a caller forge whole log entries.
            when(stock.availabilityFor(anyList()))
                    .thenReturn(List.of(availability("SKU-A", 1, 0, true)));

            Metadata metadata = new Metadata();
            metadata.put(GrpcMetadataKeys.CORRELATION_ID, "abc\", \"level\": \"FATAL");
            checkWith(metadata);

            assertThat(probe.correlationId.get()).doesNotContain("FATAL");
        }

        private void checkWith(Metadata metadata) {
            client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .checkStock(CheckStockRequest.newBuilder().setProductSku("SKU-A").build());
        }
    }

    // --- Harness -------------------------------------------------------------

    /** Stands in for the authentication interceptor, publishing roles the client asked for. */
    private static final class RoleStub implements ServerInterceptor {

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

            String roles = headers.get(ROLES_KEY);
            if (roles == null) {
                return next.startCall(call, headers);
            }
            return Contexts.interceptCall(
                    Context.current().withValue(GrpcCallContext.ROLES, Set.of(roles.split(","))),
                    call, headers, next);
        }
    }

    /** Captures what the correlation interceptor put into the context, for assertions. */
    private static final class ContextProbe implements ServerInterceptor {

        private final AtomicReference<String> correlationId = new AtomicReference<>();
        private final AtomicReference<String> requestId = new AtomicReference<>();
        private final AtomicReference<String> callerService = new AtomicReference<>();

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

            correlationId.set(GrpcCallContext.CORRELATION_ID.get());
            requestId.set(GrpcCallContext.REQUEST_ID.get());
            callerService.set(GrpcCallContext.CALLER_SERVICE.get());
            return next.startCall(call, headers);
        }
    }

    /** Collects the single response of a client-streaming call. */
    private static final class Uploaded {

        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<BulkAdjustStockSummary> summary = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        StreamObserver<BulkAdjustStockSummary> observer() {
            return new StreamObserver<>() {
                @Override
                public void onNext(BulkAdjustStockSummary value) {
                    summary.set(value);
                }

                @Override
                public void onError(Throwable throwable) {
                    failure.set(throwable);
                    done.countDown();
                }

                @Override
                public void onCompleted() {
                    done.countDown();
                }
            };
        }
    }

    private InventoryServiceGrpc.InventoryServiceStub asyncAsAdmin() {
        Metadata metadata = new Metadata();
        metadata.put(ROLES_KEY, "ROLE_ADMIN");
        return asyncClient.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    // --- Fixtures ------------------------------------------------------------

    private static StockAvailability availability(String sku, int available, int reserved,
            boolean tracked) {
        return new StockAvailability(sku, available, reserved, tracked, Instant.now());
    }

    private static StockChangedEvent change(String sku, StockChangedEvent.Reason reason) {
        return new StockChangedEvent(sku, 4, 3, Instant.now(), reason);
    }

    private static CheckStockRequest item(String sku, int quantity) {
        return CheckStockRequest.newBuilder().setProductSku(sku).setRequestedQuantity(quantity).build();
    }

    private static StockAdjustment adjustment(String sku, int delta) {
        return StockAdjustment.newBuilder()
                .setProductSku(sku).setQuantityDelta(delta).setReference("stock-take-1").build();
    }

    private static ReserveStockRequest reservationRequest() {
        return ReserveStockRequest.newBuilder()
                .setEventId("evt-1")
                .setOrderNumber("ORD-20260721-A1B2C3D4")
                .addLines(ReservationLine.newBuilder().setProductSku("SKU-A").setQuantity(5))
                .build();
    }

    private static ReservationResult reserved() {
        return new ReservationResult(ReservationResult.Outcome.RESERVED, List.of(),
                ReservationResult.Outcome.RESERVED);
    }

    private static StreamObserver<StockUpdate> collector(List<StockUpdate> into, CountDownLatch latch) {
        return new StreamObserver<>() {
            @Override
            public void onNext(StockUpdate value) {
                into.add(value);
                latch.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };
    }

    private static Status.Code codeOf(Runnable call) {
        try {
            call.run();
            return null;
        } catch (StatusRuntimeException failure) {
            return failure.getStatus().getCode();
        }
    }

    private static StatusRuntimeException failureOf(Runnable call) {
        try {
            call.run();
            throw new AssertionError("Expected the call to fail with a status");
        } catch (StatusRuntimeException failure) {
            return failure;
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && !condition.getAsBoolean()) {
            Thread.onSpinWait();
        }
    }
}
