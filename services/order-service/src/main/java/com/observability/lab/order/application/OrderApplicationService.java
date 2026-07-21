package com.observability.lab.order.application;

import com.observability.lab.order.domain.Order;
import com.observability.lab.order.domain.OrderErrorCode;
import com.observability.lab.order.domain.OrderItem;
import com.observability.lab.order.domain.OrderStatus;
import com.observability.lab.shared.exception.BusinessException;
import com.observability.lab.shared.exception.ResourceNotFoundException;
import com.observability.lab.shared.exception.TechnicalException;
import com.observability.lab.shared.logging.LogContext;
import com.observability.lab.shared.tracing.Spans;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order use cases.
 *
 * <p>This is where the transaction boundary sits. The domain enforces its own invariants and knows
 * nothing about transactions, persistence or caching; the controller knows nothing about any of them
 * either. Everything technical about "one unit of work" is decided here.
 */
@Service
@Transactional
public class OrderApplicationService {

    /**
     * Name of the cache holding single orders.
     *
     * <p>Public and constant because the caching annotations below need a compile-time constant, and
     * the cache configuration needs the same value. Two string literals would drift.
     */
    public static final String ORDERS_CACHE = "orders";

    private static final Logger log = LoggerFactory.getLogger(OrderApplicationService.class);

    private final OrderRepository orders;
    private final OrderNumberGenerator orderNumbers;
    private final ApplicationEventPublisher events;
    private final InvoiceArchive invoices;
    private final InvoiceRenderer invoiceRenderer;
    private final OrderMetrics metrics;
    private final Duration invoiceUrlValidity;

    public OrderApplicationService(OrderRepository orders, OrderNumberGenerator orderNumbers,
            ApplicationEventPublisher events, InvoiceArchive invoices,
            InvoiceRenderer invoiceRenderer, OrderMetrics metrics,
            @Value("${app.invoice.url-validity}") Duration invoiceUrlValidity) {
        this.orders = orders;
        this.orderNumbers = orderNumbers;
        this.events = events;
        this.invoices = invoices;
        this.invoiceRenderer = invoiceRenderer;
        this.metrics = metrics;
        this.invoiceUrlValidity = invoiceUrlValidity;
    }

    /**
     * Accepts an order.
     *
     * <p>The order is persisted as {@code PENDING} and an event is raised. Nothing here waits for
     * stock: the Inventory Service confirms or rejects later, and until then the order is honestly
     * described as pending rather than optimistically as confirmed.
     */
    public OrderView create(CreateOrderCommand command) {
        List<OrderItem> lines = command.items().stream()
                .map(line -> OrderItem.of(line.productSku(), line.quantity(), line.unitPrice()))
                .toList();

        Order order = Order.create(
                orderNumbers.next(), command.customerId(), command.currency(), lines);
        Order saved = orders.save(order);

        // Raised in-process. It reaches Kafka only after this transaction commits, so an order that
        // rolls back never announces itself. See KafkaOrderEventPublisher.
        events.publishEvent(OrderCreatedEvent.from(saved));

        metrics.orderAccepted(saved.getCurrency(), saved.getTotalAmount());

        // The agent's span for this request knows the URL and the status code. It cannot know which
        // order this was, which is exactly what someone searching a trace by order number needs.
        Spans.attribute(Spans.ORDER_NUMBER, saved.getOrderNumber());
        Spans.attribute(Spans.CUSTOMER_ID, saved.getCustomerId());
        Spans.attribute(Spans.CURRENCY, saved.getCurrency());
        Spans.attribute(Spans.ORDER_TOTAL, saved.getTotalAmount().doubleValue());
        Spans.attribute(Spans.LINE_COUNT, lines.size());

        try (var scope = LogContext.with("order_number", saved.getOrderNumber())
                .and("customer_id", saved.getCustomerId())) {
            log.info("Order accepted with {} line(s), total {} {}",
                    lines.size(), saved.getTotalAmount(), saved.getCurrency());
        }
        return OrderView.from(saved);
    }

    /**
     * Reads one order, from the cache when possible.
     *
     * <p>Cached because a single order is read far more often than it changes — a client polling for
     * confirmation hits this repeatedly. Every method that can change an order evicts the same key,
     * so the cache cannot serve a stale status.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = ORDERS_CACHE, key = "#orderNumber")
    public OrderView findByOrderNumber(String orderNumber) {
        return OrderView.from(require(orderNumber));
    }

    /**
     * Lists orders, optionally filtered.
     *
     * <p>Not cached. The result depends on filters, page and sort, so the key space is effectively
     * unbounded and the hit rate would be close to zero while evicting correctly on any write would
     * be impossible.
     */
    @Transactional(readOnly = true)
    public Page<OrderView> search(String customerId, OrderStatus status, Pageable pageable) {
        return orders.search(customerId, status, pageable).map(OrderView::from);
    }

    /**
     * Applies the Inventory Service's verdict to a pending order.
     *
     * <p>The closing half of the order flow. The order was accepted as {@code PENDING} without
     * knowing whether the stock existed; this is where it finds out and becomes {@code CONFIRMED} or
     * {@code REJECTED}.
     *
     * <p>Deliberately tolerant, because it is driven by an at-least-once event stream and must be
     * safe to call more than once with the same settlement:
     *
     * <ul>
     *   <li><strong>Already in the target state</strong> — a redelivery. Nothing to do, and treating
     *       it as an error would send a perfectly ordinary duplicate to the dead-letter topic.
     *   <li><strong>Cannot legally reach the target state</strong> — almost always an order the
     *       customer cancelled while the reservation was in flight. Logged and left alone rather than
     *       forced: the aggregate's transition rules are not negotiable just because an event
     *       arrived late. Releasing the stock that reservation holds is the compensating action, and
     *       it belongs with the cancellation flow rather than here.
     * </ul>
     *
     * @return the order as it now stands
     */
    @CacheEvict(cacheNames = ORDERS_CACHE, key = "#orderNumber")
    public OrderView settle(String orderNumber, boolean stockReserved) {
        Order order = require(orderNumber);
        OrderStatus target = stockReserved ? OrderStatus.CONFIRMED : OrderStatus.REJECTED;

        try (var scope = LogContext.with("order_number", orderNumber)) {
            if (order.getStatus() == target) {
                Spans.event("order.settlement.redelivery");
                log.debug("Order is already {}; settlement was a redelivery", target);
                return OrderView.from(order);
            }
            if (!order.getStatus().canTransitionTo(target)) {
                // Not an error status: an order cancelled while its reservation was in flight is
                // the system behaving correctly, and marking the span failed would put ordinary
                // operation into the error rate every alert is built on.
                Spans.attribute(Spans.OUTCOME, "transition_not_allowed");
                Spans.event("order.settlement.unapplied");
                log.warn("Order is {} and cannot become {}; leaving the settlement unapplied",
                        order.getStatus(), target);
                return OrderView.from(order);
            }

            if (stockReserved) {
                order.confirm();
            } else {
                order.reject();
            }
            Order saved = orders.save(order);
            metrics.orderSettled(target);

            Spans.attribute(Spans.ORDER_NUMBER, orderNumber);
            Spans.attribute(Spans.ORDER_STATUS, target.name());
            // An event rather than a child span: settling is a moment, not a duration, and a
            // zero-length span on a waterfall is noise.
            Spans.event("order.settled");

            log.info("Order settled as {}", target);
            return OrderView.from(saved);
        }
    }

    /**
     * Hands out a temporary link to an order's invoice.
     *
     * <p>Rebuilds the invoice when object storage does not have one. The archive is written after the
     * order commits and that write can fail — the bucket may have been briefly unreachable — so
     * treating a missing object as an error would turn a transient upload failure into a permanently
     * broken invoice. The document is derived from the order, so regenerating it is always possible
     * and always produces the same answer for an unchanged order.
     *
     * <p>Not cached: the URL embeds an expiry, so a cached one would eventually be served after it had
     * already stopped working.
     */
    @Transactional(readOnly = true)
    public InvoiceLink invoiceFor(String orderNumber) {
        // Loaded first so an unknown order is a 404 from the domain, rather than an empty result
        // from a bucket that was never going to contain it.
        Order order = require(orderNumber);

        if (!invoices.contains(orderNumber)) {
            log.info("No archived invoice for order '{}'; rebuilding it", orderNumber);
            invoices.store(invoiceRenderer.render(OrderView.from(order)));
        }

        URI url = invoices.temporaryUrl(orderNumber, invoiceUrlValidity)
                .orElseThrow(() -> new TechnicalException(
                        "The invoice for order '" + orderNumber
                                + "' was stored but could not be signed."));

        return new InvoiceLink(url, Instant.now().plus(invoiceUrlValidity));
    }

    /** Withdraws an order. Refused by the aggregate if the status does not allow it. */
    @CacheEvict(cacheNames = ORDERS_CACHE, key = "#orderNumber")
    public OrderView cancel(String orderNumber) {
        Order order = require(orderNumber);
        order.cancel();
        Order saved = orders.save(order);
        metrics.orderCancelled();

        try (var scope = LogContext.with("order_number", orderNumber)) {
            log.info("Order cancelled");
        }
        return OrderView.from(saved);
    }

    /**
     * Removes an order outright.
     *
     * <p>Only permitted once the order is already cancelled or rejected. Deleting a live order would
     * leave the Inventory Service holding a reservation for something that no longer exists.
     */
    @CacheEvict(cacheNames = ORDERS_CACHE, key = "#orderNumber")
    public void delete(String orderNumber) {
        Order order = require(orderNumber);
        if (!order.isDeletable()) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_DELETABLE,
                    "Order '" + orderNumber + "' is " + order.getStatus()
                            + " and must be cancelled before it can be deleted.");
        }
        orders.delete(order);

        try (var scope = LogContext.with("order_number", orderNumber)) {
            log.info("Order deleted");
        }
    }

    private Order require(String orderNumber) {
        return orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException(OrderErrorCode.ORDER_NOT_FOUND,
                        "Order '" + orderNumber + "' was not found."));
    }
}
