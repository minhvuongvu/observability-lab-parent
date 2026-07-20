package com.observability.lab.inventory.application;

import com.observability.lab.inventory.domain.InventoryErrorCode;
import com.observability.lab.inventory.domain.StockLevel;
import com.observability.lab.shared.exception.BusinessException;
import com.observability.lab.shared.exception.ResourceNotFoundException;
import com.observability.lab.shared.logging.LogContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stock use cases.
 *
 * <p>Owns the transaction boundary. The domain enforces its own invariants and knows nothing about
 * transactions, persistence or caching; the controller and the Kafka listener know nothing about
 * them either.
 */
@Service
@Transactional
public class StockApplicationService {

    /** Must be a compile-time constant to be usable in the caching annotations. */
    public static final String STOCK_CACHE = "stock";

    private static final String ORDER_CREATED = "order-created";

    private static final Logger log = LoggerFactory.getLogger(StockApplicationService.class);

    private final StockLevelRepository stockLevels;
    private final ProcessedEventRepository processedEvents;
    private final CacheManager cacheManager;

    public StockApplicationService(StockLevelRepository stockLevels,
            ProcessedEventRepository processedEvents, CacheManager cacheManager) {
        this.stockLevels = stockLevels;
        this.processedEvents = processedEvents;
        this.cacheManager = cacheManager;
    }

    // --- CRUD ---------------------------------------------------------------

    /** Starts tracking a product. */
    public StockLevelView create(String productSku, int initialQuantity) {
        if (stockLevels.existsByProductSku(productSku)) {
            throw new BusinessException(InventoryErrorCode.DUPLICATE_SKU,
                    "Stock is already tracked for '" + productSku + "'.");
        }
        StockLevel saved = stockLevels.save(StockLevel.create(productSku, initialQuantity));

        try (var scope = LogContext.with("product_sku", saved.getProductSku())) {
            log.info("Stock tracking started with {} unit(s)", saved.getAvailableQuantity());
        }
        return StockLevelView.from(saved);
    }

    /**
     * Reads one stock level, from the cache when warm.
     *
     * <p>This is the endpoint the Order Service calls before accepting an order, so it is read far
     * more often than it changes. The TTL is short because a stale availability figure is the one
     * that oversells.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = STOCK_CACHE, key = "#productSku")
    public StockLevelView findByProductSku(String productSku) {
        return StockLevelView.from(require(productSku));
    }

    /** Lists tracked products. Not cached: the key space is the filter and page combination. */
    @Transactional(readOnly = true)
    public Page<StockLevelView> list(Pageable pageable) {
        return stockLevels.findAll(pageable).map(StockLevelView::from);
    }

    /** Records an arrival of units. */
    @CacheEvict(cacheNames = STOCK_CACHE, key = "#productSku")
    public StockLevelView receive(String productSku, int quantity, String reference) {
        StockLevel stock = require(productSku);
        stock.receive(quantity, reference);
        return StockLevelView.from(stockLevels.save(stock));
    }

    /** Undoes a reservation, normally because an order was cancelled. */
    @CacheEvict(cacheNames = STOCK_CACHE, key = "#productSku")
    public StockLevelView release(String productSku, int quantity, String reference) {
        StockLevel stock = require(productSku);
        stock.release(quantity, reference);
        return StockLevelView.from(stockLevels.save(stock));
    }

    /** Applies an operator correction, for example after a physical count. */
    @CacheEvict(cacheNames = STOCK_CACHE, key = "#productSku")
    public StockLevelView adjust(String productSku, int quantity, String reference) {
        StockLevel stock = require(productSku);
        stock.adjust(quantity, reference);
        return StockLevelView.from(stockLevels.save(stock));
    }

    /** Stops tracking a product. Refused while units are still promised to orders. */
    @CacheEvict(cacheNames = STOCK_CACHE, key = "#productSku")
    public void delete(String productSku) {
        StockLevel stock = require(productSku);
        if (!stock.isRemovable()) {
            throw new BusinessException(InventoryErrorCode.STOCK_STILL_RESERVED,
                    "'" + productSku + "' has " + stock.getReservedQuantity()
                            + " unit(s) reserved and cannot be removed.");
        }
        stockLevels.delete(stock);

        try (var scope = LogContext.with("product_sku", productSku)) {
            log.info("Stock tracking stopped");
        }
    }

    // --- Event handling -----------------------------------------------------

    /**
     * Reserves stock for an accepted order.
     *
     * <p>Three properties, each of which is the reason for a piece of the shape below:
     *
     * <ol>
     *   <li><strong>Idempotent.</strong> The event id is checked first and recorded in the same
     *       transaction as the reservation, so a redelivery cannot reserve twice.
     *   <li><strong>All or nothing.</strong> Availability is checked for every line before any line
     *       is changed. Reserving what is available and failing on the rest would leave an order
     *       half-promised, with no record of which half.
     *   <li><strong>A shortage is an answer, not a failure.</strong> It returns {@code REJECTED} and
     *       still records the event as handled, because retrying it would produce the same shortage
     *       forever.
     * </ol>
     *
     * <p>Telling the Order Service the outcome is the integration step's job; this returns it.
     */
    public ReservationResult reserveForOrder(String eventId, String orderNumber,
            List<ReservationLine> lines) {

        if (processedEvents.hasProcessed(eventId)) {
            log.info("Event '{}' for order '{}' was already applied; ignoring redelivery",
                    eventId, orderNumber);
            return ReservationResult.alreadyProcessed();
        }

        // One query for every line, not one per line.
        Map<String, StockLevel> tracked = new LinkedHashMap<>();
        stockLevels.findAllByProductSkuIn(lines.stream().map(ReservationLine::productSku).toList())
                .forEach(stock -> tracked.put(stock.getProductSku(), stock));

        List<String> shortages = shortagesFor(lines, tracked);
        if (!shortages.isEmpty()) {
            processedEvents.markProcessed(eventId, ORDER_CREATED);
            try (var scope = LogContext.with("order_number", orderNumber)) {
                // INFO, not ERROR: refusing to promise units that do not exist is this service
                // working correctly.
                log.info("Reservation rejected for order '{}': {}", orderNumber, shortages);
            }
            return ReservationResult.rejected(shortages);
        }

        lines.forEach(line -> tracked.get(line.productSku()).reserve(line.quantity(), orderNumber));
        tracked.values().forEach(stockLevels::save);
        processedEvents.markProcessed(eventId, ORDER_CREATED);

        evictCached(tracked.keySet());

        try (var scope = LogContext.with("order_number", orderNumber)) {
            log.info("Reserved {} line(s) for order '{}'", lines.size(), orderNumber);
        }
        return ReservationResult.reserved();
    }

    /** Products that cannot satisfy their requested quantity, including untracked ones. */
    private static List<String> shortagesFor(List<ReservationLine> lines,
            Map<String, StockLevel> tracked) {

        List<String> shortages = new ArrayList<>();
        for (ReservationLine line : lines) {
            StockLevel stock = tracked.get(line.productSku());
            if (stock == null) {
                shortages.add(line.productSku() + " (not tracked)");
            } else if (stock.getAvailableQuantity() < line.quantity()) {
                shortages.add(line.productSku() + " (requested " + line.quantity()
                        + ", available " + stock.getAvailableQuantity() + ")");
            }
        }
        return shortages;
    }

    /**
     * Evicts the SKUs this operation touched.
     *
     * <p>Done through the {@link CacheManager} rather than {@code @CacheEvict} because an order
     * changes several products at once and the annotation can only name one key. The alternative,
     * {@code allEntries = true}, would empty the cache on every order and leave it permanently cold
     * under load — which is the opposite of what it is for.
     *
     * <p>Eviction happens before the transaction commits, so a concurrent read can briefly
     * repopulate the entry with the pre-commit value. The window is milliseconds and bounded by the
     * short TTL; closing it properly needs an after-commit hook, which arrives with the integration
     * step alongside the rest of the consistency work.
     */
    private void evictCached(Collection<String> productSkus) {
        Cache cache = cacheManager.getCache(STOCK_CACHE);
        if (cache != null) {
            productSkus.forEach(cache::evict);
        }
    }

    private StockLevel require(String productSku) {
        return stockLevels.findByProductSku(productSku)
                .orElseThrow(() -> new ResourceNotFoundException(InventoryErrorCode.STOCK_NOT_FOUND,
                        "No stock level is recorded for '" + productSku + "'."));
    }
}
