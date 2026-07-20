package com.observability.lab.inventory.domain;

import com.observability.lab.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * One recorded change to a stock level.
 *
 * <p>Append-only: there is no setter and no update path. A movement describes something that already
 * happened, and rewriting history would defeat the only mechanism available for explaining how a
 * stock level reached its current value.
 *
 * <p>The {@code reference} carries the order number that caused the change, which is what lets a
 * discrepancy be traced back to a specific order rather than merely observed.
 */
@Entity
@Table(name = "stock_movements")
public class StockMovement extends BaseEntity<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "stock_movement_id_generator")
    // allocationSize must equal the sequence's INCREMENT BY in V1__create_inventory_schema.sql.
    @SequenceGenerator(name = "stock_movement_id_generator", sequenceName = "stock_movement_id_seq",
            allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_level_id", nullable = false)
    private StockLevel stockLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 20)
    private MovementType movementType;

    /** Always positive. The direction is carried by {@link #movementType}, not by the sign. */
    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "reference", length = 64)
    private String reference;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** For JPA only. */
    protected StockMovement() {}

    /** Created by the aggregate root; never constructed directly by application code. */
    static StockMovement of(StockLevel stockLevel, MovementType type, int quantity, String reference) {
        StockMovement movement = new StockMovement();
        movement.stockLevel = Objects.requireNonNull(stockLevel, "stockLevel");
        movement.movementType = Objects.requireNonNull(type, "movementType");
        movement.quantity = quantity;
        movement.reference = reference;
        movement.occurredAt = Instant.now();
        return movement;
    }

    @Override
    public Long getId() {
        return id;
    }

    public StockLevel getStockLevel() {
        return stockLevel;
    }

    public MovementType getMovementType() {
        return movementType;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getReference() {
        return reference;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
