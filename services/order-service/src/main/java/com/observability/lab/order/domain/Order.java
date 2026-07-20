package com.observability.lab.order.domain;

import com.observability.lab.shared.exception.BusinessException;
import com.observability.lab.shared.exception.ValidationException;
import com.observability.lab.shared.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The order aggregate root.
 *
 * <p>Everything that can change an order goes through a method on this class. There is no setter for
 * {@code status} and no way to add a line after construction, which is what lets the class promise
 * that the total always equals the sum of the lines and that the status only ever moves along a
 * legal edge of {@link OrderStatus}.
 *
 * <p>The business rules are intentionally thin — this lab is about operating a distributed system,
 * not about modelling commerce. They are real rules nonetheless, and they are enforced here rather
 * than in a service, so a second caller cannot bypass them.
 */
@Entity
@Table(name = "orders")
public class Order extends AuditableEntity<Long> {

    private static final int CURRENCY_CODE_LENGTH = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_id_generator")
    // allocationSize must equal the sequence's INCREMENT BY in V1__create_order_schema.sql.
    @SequenceGenerator(name = "order_id_generator", sequenceName = "order_id_seq", allocationSize = 50)
    private Long id;

    /**
     * The identifier callers use. Kept distinct from the surrogate key so the internal id stays an
     * implementation detail that can change without breaking a published URL.
     */
    @Column(name = "order_number", nullable = false, unique = true, length = 40)
    private String orderNumber;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = CURRENCY_CODE_LENGTH)
    private String currency;

    /**
     * Cascade and orphan removal because the lines are part of the aggregate: they have no life of
     * their own and deleting the order must delete them.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    // Listing a page of orders and reading each one's lines is the classic N+1: one query for the
    // page, then one per order. BatchSize turns that into one query per 50 orders. An entity graph
    // would be the alternative, but fetching a collection alongside pagination forces Hibernate to
    // page in memory, which is worse.
    @org.hibernate.annotations.BatchSize(size = 50)
    private List<OrderItem> items = new ArrayList<>();

    /** For JPA only. */
    protected Order() {}

    /**
     * Creates a new order in {@link OrderStatus#PENDING}.
     *
     * <p>Pending, not confirmed: at this point nothing has reserved stock. The order becomes
     * confirmed only once the Inventory Service says so, which is what makes this an eventually
     * consistent flow rather than a distributed transaction.
     *
     * @throws ValidationException when the order could not describe a real purchase
     */
    public static Order create(String orderNumber, String customerId, String currency,
            List<OrderItem> lines) {

        requireText(orderNumber, "orderNumber");
        requireText(customerId, "customerId");
        requireText(currency, "currency");
        if (currency.length() != CURRENCY_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "currency must be a 3-letter ISO code, was '" + currency + "'");
        }
        if (lines == null || lines.isEmpty()) {
            throw new ValidationException(OrderErrorCode.EMPTY_ORDER,
                    OrderErrorCode.EMPTY_ORDER.defaultMessage(), List.of());
        }

        Order order = new Order();
        order.orderNumber = orderNumber.trim();
        order.customerId = customerId.trim();
        order.currency = currency.trim().toUpperCase(java.util.Locale.ROOT);
        order.status = OrderStatus.PENDING;
        lines.forEach(order::attach);
        order.recalculateTotal();
        return order;
    }

    private void attach(OrderItem line) {
        line.attachTo(this);
        items.add(line);
    }

    /**
     * Recomputed from the lines rather than accepted from the caller.
     *
     * <p>A total supplied by a client is a total a client can get wrong, or lie about.
     */
    private void recalculateTotal() {
        this.totalAmount = items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Stock was reserved. */
    public void confirm() {
        transitionTo(OrderStatus.CONFIRMED);
    }

    /** Stock could not be reserved. */
    public void reject() {
        transitionTo(OrderStatus.REJECTED);
    }

    /** Withdrawn by the customer or an operator. */
    public void cancel() {
        transitionTo(OrderStatus.CANCELLED);
    }

    private void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            // A BusinessException, not a technical one: refusing an illegal move is the aggregate
            // doing its job. It surfaces as 422 and is logged at INFO.
            throw new BusinessException(OrderErrorCode.ILLEGAL_STATUS_TRANSITION,
                    "Order '" + orderNumber + "' is " + status + " and cannot become " + target + ".");
        }
        this.status = target;
    }

    /** Whether this order may be removed outright rather than cancelled. */
    public boolean isDeletable() {
        return status == OrderStatus.CANCELLED || status == OrderStatus.REJECTED;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    @Override
    public Long getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getCustomerId() {
        return customerId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    /** Unmodifiable: lines are added only through {@link #create}, never afterwards. */
    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
