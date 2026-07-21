package com.observability.lab.order.application;

import com.observability.lab.order.infrastructure.grpc.ExpressReservation;
import com.observability.lab.order.infrastructure.grpc.InventoryGrpcClient;
import com.observability.lab.shared.logging.LogContext;
import com.observability.lab.shared.tracing.Spans;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Races the Kafka path with a synchronous reservation, and loses gracefully.
 *
 * <p>An accepted order is {@code PENDING} until the Inventory Service settles it over Kafka. That is
 * the invariant and it does not change here. What this adds is an attempt to get the same answer
 * immediately, under a deliberately tight deadline, so the common case — inventory is up, stock
 * exists — confirms in milliseconds instead of after a round trip through a broker.
 *
 * <p><strong>Why this is safe.</strong> Both paths carry the same {@code event_id}, and the Inventory
 * Service's {@code processed_events} table deduplicates on it. Whichever arrives second is a no-op
 * that replays the recorded decision. The synchronous attempt is a latency optimisation that cannot
 * change the outcome — and that is the only reason it is allowed to exist.
 *
 * <p><strong>Why {@code AFTER_COMMIT}.</strong> The outbox row is written by
 * {@code OutboxOrderEventPublisher} at {@code BEFORE_COMMIT}, so by the time this runs the Kafka
 * event is already durable. This attempt can fail in any way it likes — deadline, circuit open,
 * inventory down — and the order still gets settled. A failure here is not a failure of the order,
 * and it is never allowed to become one.
 */
@Component
// Both flags, and deliberately not @ConditionalOnBean(InventoryGrpcClient.class): that condition is
// only reliable inside an auto-configuration, because on a component-scanned class it is evaluated
// before the ordering of other scanned @Configuration classes is settled. Gating on the same
// property that creates the client gives the same answer without depending on scan order.
@ConditionalOnProperty(prefix = "app.grpc.client.inventory",
        name = {"enabled", "express-reservation"}, havingValue = "true", matchIfMissing = true)
public class ExpressReservationListener {

    private static final Logger log = LoggerFactory.getLogger(ExpressReservationListener.class);

    private final InventoryGrpcClient inventory;
    private final OrderApplicationService orders;

    public ExpressReservationListener(InventoryGrpcClient inventory, OrderApplicationService orders) {
        this.inventory = inventory;
        this.orders = orders;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        List<CreateOrderCommand.Line> lines = event.items().stream()
                .map(item -> new CreateOrderCommand.Line(
                        item.productSku(), item.quantity(), BigDecimal.ZERO))
                .toList();

        ExpressReservation reservation =
                inventory.reserve(event.eventId(), event.orderNumber(), lines);

        try (var scope = LogContext.with("order_number", event.orderNumber())) {
            if (!reservation.settled()) {
                // Not a warning. The order is PENDING, which is precisely what it would have been
                // had this attempt never been made, and the Kafka path will settle it.
                Spans.event("order.express_reservation.deferred");
                log.debug("Express reservation did not answer in time; leaving the order to Kafka");
                return;
            }

            boolean reserved = reservation.outcome() == ExpressReservation.Outcome.RESERVED;
            orders.settleExpress(event.orderNumber(), reserved);

            Spans.attribute(Spans.OUTCOME, reserved ? "express_reserved" : "express_rejected");
            log.info("Express reservation settled the order as {}{}",
                    reserved ? "CONFIRMED" : "REJECTED",
                    reservation.replayed() ? " (replayed)" : "");

        } catch (RuntimeException failure) {
            // Swallowed deliberately, and this is the load-bearing line of the class. An exception
            // escaping an AFTER_COMMIT listener does not roll the order back - it is already
            // committed - but it does propagate to the caller, turning a successful order into a
            // 500 because an *optimisation* failed.
            log.warn("Express reservation for order '{}' could not be applied: {}",
                    event.orderNumber(), failure.toString());
        }
    }
}
