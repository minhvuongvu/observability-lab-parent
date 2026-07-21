package com.observability.lab.inventory.application;

import java.util.List;

/**
 * What happened when an order asked for stock.
 *
 * <p>A returned value rather than an exception, because none of the three outcomes is a failure of
 * this service. "Not enough stock" is an answer; so is "I have already handled this event". Throwing
 * for either would make the consumer's error handler decide business questions, and would put
 * ordinary operation into the error rate that alerting is built on.
 *
 * @param outcome    what this delivery did
 * @param shortages  products that could not be satisfied, empty unless the settlement is a refusal
 * @param settlement the decision to announce to the Order Service: {@code RESERVED} or
 *                   {@code REJECTED}, and on a redelivery the decision the <em>first</em> delivery
 *                   made. Null only when an old row carries no recorded decision, in which case
 *                   there is nothing left to announce.
 */
public record ReservationResult(Outcome outcome, List<String> shortages, Outcome settlement) {

    public enum Outcome {
        /** Every line was reserved. */
        RESERVED,

        /** At least one line could not be satisfied. Nothing was reserved. */
        REJECTED,

        /** This event was applied by an earlier delivery. Nothing changed. */
        ALREADY_PROCESSED
    }

    public ReservationResult {
        shortages = shortages == null ? List.of() : List.copyOf(shortages);
    }

    /**
     * Whether this delivery changed anything.
     *
     * <p>Distinct from having something to announce: a redelivery changes nothing and still owes the
     * Order Service the original settlement, because the reason it was redelivered may well be that
     * the announcement never arrived.
     */
    public boolean changedStock() {
        return outcome == Outcome.RESERVED;
    }

    /** Whether there is a decision worth publishing. */
    public boolean hasSettlement() {
        return settlement != null;
    }

    static ReservationResult reserved() {
        return new ReservationResult(Outcome.RESERVED, List.of(), Outcome.RESERVED);
    }

    static ReservationResult rejected(List<String> shortages) {
        return new ReservationResult(Outcome.REJECTED, shortages, Outcome.REJECTED);
    }

    /**
     * A delivery that changed nothing, carrying whatever the first delivery decided.
     *
     * <p>Replaying that decision is what lets a failed publish be retried safely: the effect happens
     * once, the announcement as many times as it takes.
     */
    static ReservationResult alreadyProcessed(Outcome recorded, List<String> shortages) {
        return new ReservationResult(Outcome.ALREADY_PROCESSED, shortages, recorded);
    }
}
