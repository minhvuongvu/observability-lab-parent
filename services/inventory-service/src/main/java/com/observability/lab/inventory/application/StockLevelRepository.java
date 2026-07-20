package com.observability.lab.inventory.application;

import com.observability.lab.inventory.domain.StockLevel;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * What the application layer needs from storage, without naming a storage technology.
 *
 * <p>Declared in the application layer because it is the use cases that decide which outbound
 * capabilities they require. The implementation lives in {@code infrastructure.persistence} and is
 * the only place Spring Data appears.
 *
 * <p>Everything is addressed by product SKU. The surrogate id is an implementation detail of the
 * table and nothing above persistence should depend on it.
 */
public interface StockLevelRepository {

    StockLevel save(StockLevel stockLevel);

    Optional<StockLevel> findByProductSku(String productSku);

    /**
     * Loads several stock levels at once.
     *
     * <p>Exists so reserving a multi-line order is one query rather than one per line. The
     * difference is invisible with two lines and decisive with fifty.
     */
    List<StockLevel> findAllByProductSkuIn(Collection<String> productSkus);

    boolean existsByProductSku(String productSku);

    Page<StockLevel> findAll(Pageable pageable);

    void delete(StockLevel stockLevel);
}
