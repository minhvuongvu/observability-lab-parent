package com.observability.lab.inventory.infrastructure.persistence;

import com.observability.lab.inventory.domain.StockLevel;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the stock levels table.
 *
 * <p>One of only two types in the service that name Spring Data. Everything above depends on the
 * {@code StockLevelRepository} port.
 */
public interface StockLevelJpaRepository extends JpaRepository<StockLevel, Long> {

    Optional<StockLevel> findByProductSku(String productSku);

    /** One query for a whole order's worth of products, rather than one per line. */
    List<StockLevel> findAllByProductSkuIn(Collection<String> productSkus);

    boolean existsByProductSku(String productSku);
}
