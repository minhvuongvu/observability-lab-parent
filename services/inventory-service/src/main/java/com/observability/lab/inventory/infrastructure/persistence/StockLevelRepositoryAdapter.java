package com.observability.lab.inventory.infrastructure.persistence;

import com.observability.lab.inventory.application.StockLevelRepository;
import com.observability.lab.inventory.domain.InventoryErrorCode;
import com.observability.lab.inventory.domain.StockLevel;
import com.observability.lab.inventory.domain.StockMovement;
import com.observability.lab.shared.exception.BusinessException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * Adapts Spring Data to the {@code StockLevelRepository} port.
 *
 * <p>Two responsibilities beyond delegation:
 *
 * <ol>
 *   <li><strong>Writing the movement trail.</strong> The aggregate produces movements but does not
 *       hold them as a mapped collection, so saving one means saving both tables. Doing it here
 *       keeps the pairing in a single place instead of relying on every use case to remember it.
 *   <li><strong>Translating persistence failures.</strong> A {@code DataIntegrityViolationException}
 *       escaping to the controller is reported as an unexpected internal error and logged with a
 *       stack trace, when in fact it is an ordinary conflict the caller can act on.
 * </ol>
 */
@Repository
public class StockLevelRepositoryAdapter implements StockLevelRepository {

    private final StockLevelJpaRepository stockLevels;
    private final StockMovementJpaRepository movements;

    public StockLevelRepositoryAdapter(StockLevelJpaRepository stockLevels,
            StockMovementJpaRepository movements) {
        this.stockLevels = stockLevels;
        this.movements = movements;
    }

    @Override
    public StockLevel save(StockLevel stockLevel) {
        StockLevel saved;
        try {
            // Flushing here rather than at commit is what makes the violation catchable at all:
            // one raised during commit is outside any application try/catch.
            saved = stockLevels.saveAndFlush(stockLevel);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(InventoryErrorCode.DUPLICATE_SKU,
                    "Stock is already tracked for '" + stockLevel.getProductSku() + "'.", exception);
        }

        // Drained after the aggregate is saved: a newly created stock level only has the identifier
        // its movements reference once it has been written.
        List<StockMovement> pending = saved.drainPendingMovements();
        if (!pending.isEmpty()) {
            movements.saveAll(pending);
        }
        return saved;
    }

    @Override
    public Optional<StockLevel> findByProductSku(String productSku) {
        return stockLevels.findByProductSku(productSku);
    }

    @Override
    public List<StockLevel> findAllByProductSkuIn(Collection<String> productSkus) {
        return productSkus.isEmpty() ? List.of() : stockLevels.findAllByProductSkuIn(productSkus);
    }

    @Override
    public boolean existsByProductSku(String productSku) {
        return stockLevels.existsByProductSku(productSku);
    }

    @Override
    public Page<StockLevel> findAll(Pageable pageable) {
        return stockLevels.findAll(pageable);
    }

    @Override
    public void delete(StockLevel stockLevel) {
        // The movement rows go with it: the foreign key is declared ON DELETE CASCADE, so the
        // trail is removed by the database rather than by a second query from here.
        stockLevels.delete(stockLevel);
    }
}
