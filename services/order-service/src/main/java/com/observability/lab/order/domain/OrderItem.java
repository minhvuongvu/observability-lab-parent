package com.observability.lab.order.domain;

import com.observability.lab.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One line of an order: a product, a quantity and the price agreed at the time.
 *
 * <p>Part of the {@link Order} aggregate and never handled on its own — it is created through
 * {@link Order#create} and reached only through its order. That is what lets the order guarantee its
 * own total always matches its lines.
 *
 * <p>The unit price is stored rather than looked up. A price that changes next week must not
 * retroactively change what a customer was charged last week.
 */
@Entity
@Table(name = "order_items")
public class OrderItem extends BaseEntity<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_item_id_generator")
    // allocationSize must equal the sequence's INCREMENT BY in V1__create_order_schema.sql.
    // If they diverge, Hibernate hands out identifiers the database has already issued.
    @SequenceGenerator(name = "order_item_id_generator", sequenceName = "order_item_id_seq",
            allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_sku", nullable = false, length = 64)
    private String productSku;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;

    /** For JPA only. Protected so application code cannot build an unmapped item. */
    protected OrderItem() {}

    private OrderItem(String productSku, int quantity, BigDecimal unitPrice) {
        this.productSku = productSku;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    /**
     * Creates a line, refusing anything the database would reject anyway.
     *
     * <p>Validating here rather than only at the API boundary means the rule holds for every caller,
     * including a future Kafka consumer or batch import that never passes through a controller.
     *
     * @throws IllegalArgumentException when the line could not describe a real purchase
     */
    public static OrderItem of(String productSku, int quantity, BigDecimal unitPrice) {
        if (productSku == null || productSku.isBlank()) {
            throw new IllegalArgumentException("productSku must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, was " + quantity);
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative, was " + unitPrice);
        }
        // Normalise to the scale the column stores, so the total computed in memory equals the
        // total read back from the database.
        return new OrderItem(productSku.trim(), quantity, unitPrice.setScale(2, java.math.RoundingMode.HALF_UP));
    }

    /** Called by the aggregate root when the line joins an order. */
    void attachTo(Order owner) {
        this.order = Objects.requireNonNull(owner, "owner must not be null");
    }

    /** What this line contributes to the order total. */
    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    @Override
    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public String getProductSku() {
        return productSku;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }
}
