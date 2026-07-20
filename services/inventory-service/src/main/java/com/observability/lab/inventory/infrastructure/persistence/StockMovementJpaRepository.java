package com.observability.lab.inventory.infrastructure.persistence;

import com.observability.lab.inventory.domain.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only storage for the movement trail.
 *
 * <p>Has no port of its own: movements are written as part of saving a stock level and are never
 * read or modified by a use case, so exposing them upward would only invite someone to do both.
 */
public interface StockMovementJpaRepository extends JpaRepository<StockMovement, Long> {}
