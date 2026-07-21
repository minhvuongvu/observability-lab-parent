package com.observability.lab.inventory.application;

import java.util.Optional;

/**
 * Remembers which events have already been applied, and what they decided.
 *
 * <p>The de-duplication side of idempotent consumption. Kept behind a port of its own rather than
 * folded into {@link StockLevelRepository} because it has nothing to do with stock: the same
 * mechanism serves any consumer this service grows.
 */
public interface ProcessedEventRepository {

    /** Whether this event has already been applied. */
    boolean hasProcessed(String eventId);

    /**
     * The decision recorded for an event, if it has been applied and a decision was stored.
     *
     * <p>Empty for an unseen event, and also for one handled before outcomes were recorded. A
     * redelivery uses this to replay the original settlement rather than discard it.
     */
    Optional<RecordedOutcome> outcomeOf(String eventId);

    /**
     * Records the event as applied, together with what was decided.
     *
     * <p>Must be called inside the same transaction as the effect it accompanies. Committing them
     * separately reintroduces exactly the window this table exists to close.
     */
    void markProcessed(String eventId, String eventType, RecordedOutcome outcome);

    /**
     * A decision durable enough to be announced again.
     *
     * @param outcome what was decided
     * @param detail  why, when the decision was a refusal; empty otherwise
     */
    record RecordedOutcome(ReservationResult.Outcome outcome, String detail) {}
}
