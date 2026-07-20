package com.observability.lab.order.application;

import com.observability.lab.order.domain.Order;
import com.observability.lab.order.domain.OrderErrorCode;
import com.observability.lab.order.domain.OrderItem;
import com.observability.lab.order.domain.OrderStatus;
import com.observability.lab.shared.exception.BusinessException;
import com.observability.lab.shared.exception.ResourceNotFoundException;
import com.observability.lab.shared.logging.LogContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public OrderApplicationService(OrderRepository orders, OrderNumberGenerator orderNumbers,
            ApplicationEventPublisher events) {
        this.orders = orders;
        this.orderNumbers = orderNumbers;
        this.events = events;
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

    /** Withdraws an order. Refused by the aggregate if the status does not allow it. */
    @CacheEvict(cacheNames = ORDERS_CACHE, key = "#orderNumber")
    public OrderView cancel(String orderNumber) {
        Order order = require(orderNumber);
        order.cancel();
        Order saved = orders.save(order);

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
