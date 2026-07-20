package com.observability.lab.inventory.application;

/**
 * Remembers which events have already been applied.
 *
 * <p>The de-duplication side of idempotent consumption. Kept behind a port of its own rather than
 * folded into {@link StockLevelRepository} because it has nothing to do with stock: the same
 * mechanism serves any consumer this service grows.
 */
public interface ProcessedEventRepository {

    /** Whether this event has already been applied. */
    boolean hasProcessed(String eventId);

    /**
     * Records the event as applied.
     *
     * <p>Must be called inside the same transaction as the effect it accompanies. Committing them
     * separately reintroduces exactly the window this table exists to close.
     */
    void markProcessed(String eventId, String eventType);
}
