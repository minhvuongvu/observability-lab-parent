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
 * @param outcome   what was decided
 * @param shortages products that could not be satisfied, empty unless {@code REJECTED}
 */
public record ReservationResult(Outcome outcome, List<String> shortages) {

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

    static ReservationResult reserved() {
        return new ReservationResult(Outcome.RESERVED, List.of());
    }

    static ReservationResult rejected(List<String> shortages) {
        return new ReservationResult(Outcome.REJECTED, shortages);
    }

    static ReservationResult alreadyProcessed() {
        return new ReservationResult(Outcome.ALREADY_PROCESSED, List.of());
    }
}
