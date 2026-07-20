package com.observability.lab.inventory.domain;

import com.observability.lab.shared.exception.BusinessException;
import com.observability.lab.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.ArrayList;
import java.util.List;

/**
 * Stock held for one product. The aggregate root of this bounded context.
 *
 * <p>Two numbers, and the invariant that connects them. {@code availableQuantity} is what may still
 * be promised; {@code reservedQuantity} is what has already been promised and not yet shipped.
 * Reserving moves units from one to the other, so the total on hand is unchanged — a reservation is
 * a commitment, not a shipment.
 *
 * <p>Neither number has a setter. Every transition goes through a method that enforces its rule and
 * records a {@link StockMovement}, which is the only reason the current value is ever explicable.
 *
 * <p>Concurrency is handled by the {@code @Version} column inherited from
 * {@link com.observability.lab.shared.persistence.BaseEntity}. Two consumers reserving the same SKU
 * at once is the normal case, not the exceptional one: the second commit fails with an optimistic
 * lock exception and is retried, rather than silently overwriting the first and overselling.
 */
@Entity
@Table(name = "stock_levels")
public class StockLevel extends AuditableEntity<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "stock_level_id_generator")
    // allocationSize must equal the sequence's INCREMENT BY in V1__create_inventory_schema.sql.
    @SequenceGenerator(name = "stock_level_id_generator", sequenceName = "stock_level_id_seq",
            allocationSize = 50)
    private Long id;

    @Column(name = "product_sku", nullable = false, unique = true, length = 64)
    private String productSku;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    /**
     * Movements produced by the current unit of work, not yet written.
     *
     * <p>Transient, and deliberately <em>not</em> a mapped {@code @OneToMany}. Adding to a lazy
     * mapped collection forces Hibernate to load it first, so a single reservation would read the
     * product's entire movement history — a trail that only ever grows, and is never needed in
     * order to append to it.
     *
     * <p>The aggregate still creates every movement, so the guarantee that no change goes
     * unrecorded is intact. The persistence adapter drains this list when it saves the aggregate.
     */
    @Transient
    private final List<StockMovement> pendingMovements = new ArrayList<>();

    /** For JPA only. */
    protected StockLevel() {}

    /**
     * Starts tracking a product.
     *
     * @param productSku      the product
     * @param initialQuantity units on hand at the outset; may be zero
     */
    public static StockLevel create(String productSku, int initialQuantity) {
        if (productSku == null || productSku.isBlank()) {
            throw new IllegalArgumentException("productSku must not be blank");
        }
        if (initialQuantity < 0) {
            throw new BusinessException(InventoryErrorCode.INVALID_QUANTITY,
                    "Initial quantity must not be negative, was " + initialQuantity + ".");
        }

        StockLevel stock = new StockLevel();
        stock.productSku = productSku.trim();
        stock.availableQuantity = 0;
        stock.reservedQuantity = 0;
        if (initialQuantity > 0) {
            stock.receive(initialQuantity, "initial-stock");
        }
        return stock;
    }

    /** Units arrived and become available. */
    public void receive(int quantity, String reference) {
        requirePositive(quantity);
        availableQuantity += quantity;
        record(MovementType.RECEIPT, quantity, reference);
    }

    /**
     * Promises units to an order.
     *
     * <p>The one rule that matters in this service: never promise what is not there.
     *
     * @throws BusinessException when there is not enough available stock
     */
    public void reserve(int quantity, String reference) {
        requirePositive(quantity);
        if (availableQuantity < quantity) {
            throw new BusinessException(InventoryErrorCode.INSUFFICIENT_STOCK,
                    "Cannot reserve " + quantity + " of '" + productSku + "': only "
                            + availableQuantity + " available.");
        }
        availableQuantity -= quantity;
        reservedQuantity += quantity;
        record(MovementType.RESERVATION, quantity, reference);
    }

    /**
     * Undoes a reservation, normally because the order was cancelled.
     *
     * @throws BusinessException when more is released than was ever reserved
     */
    public void release(int quantity, String reference) {
        requirePositive(quantity);
        if (reservedQuantity < quantity) {
            throw new BusinessException(InventoryErrorCode.INSUFFICIENT_RESERVATION,
                    "Cannot release " + quantity + " of '" + productSku + "': only "
                            + reservedQuantity + " reserved.");
        }
        reservedQuantity -= quantity;
        availableQuantity += quantity;
        record(MovementType.RELEASE, quantity, reference);
    }

    /** An operator correction, for example after a physical count. */
    public void adjust(int quantity, String reference) {
        requirePositive(quantity);
        availableQuantity += quantity;
        record(MovementType.ADJUSTMENT, quantity, reference);
    }

    /**
     * Whether this product can stop being tracked.
     *
     * <p>Removing a product with outstanding reservations would strand orders that were promised
     * units the system no longer knows about.
     */
    public boolean isRemovable() {
        return reservedQuantity == 0;
    }

    /** Units physically on hand: promised and unpromised together. */
    public int totalQuantity() {
        return availableQuantity + reservedQuantity;
    }

    /**
     * Hands over the movements produced since the last drain, and forgets them.
     *
     * <p>Called by the persistence adapter after the aggregate itself is saved, at which point a
     * newly created stock level has the identifier its movements need to reference.
     */
    public List<StockMovement> drainPendingMovements() {
        List<StockMovement> drained = List.copyOf(pendingMovements);
        pendingMovements.clear();
        return drained;
    }

    private void record(MovementType type, int quantity, String reference) {
        pendingMovements.add(StockMovement.of(this, type, quantity, reference));
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new BusinessException(InventoryErrorCode.INVALID_QUANTITY,
                    "Quantity must be positive, was " + quantity + ".");
        }
    }

    @Override
    public Long getId() {
        return id;
    }

    public String getProductSku() {
        return productSku;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }
}
