package com.observability.lab.order.infrastructure.grpc;

import java.util.List;

/**
 * What the synchronous reservation attempt achieved, if anything.
 *
 * <p>{@link Outcome#UNAVAILABLE} is not a failure of the order. It means the fast path did not
 * answer in time and the order stays {@code PENDING} until the Kafka path settles it — which is what
 * it would have done anyway had the attempt never been made.
 *
 * @param outcome   what the Inventory Service decided, or that it did not answer
 * @param shortages why, when the answer was a refusal
 * @param replayed  whether this event id had already been applied, making the answer a replay of an
 *                  earlier decision rather than a new one
 */
public record ExpressReservation(Outcome outcome, List<String> shortages, boolean replayed) {

    public enum Outcome {
        /** Stock is held. The order can be confirmed immediately. */
        RESERVED,

        /** The Inventory Service refused. The order can be rejected immediately. */
        REJECTED,

        /**
         * No answer within the budget, or the circuit was open.
         *
         * <p>Deliberately indistinguishable from "not attempted": the caller's behaviour is the
         * same either way, and giving it two ways to express "wait for Kafka" would invite one of
         * them to be handled and the other forgotten.
         */
        UNAVAILABLE
    }

    public ExpressReservation {
        shortages = shortages == null ? List.of() : List.copyOf(shortages);
    }

    static ExpressReservation reserved(boolean replayed) {
        return new ExpressReservation(Outcome.RESERVED, List.of(), replayed);
    }

    static ExpressReservation rejected(List<String> shortages, boolean replayed) {
        return new ExpressReservation(Outcome.REJECTED, shortages, replayed);
    }

    static ExpressReservation unavailable() {
        return new ExpressReservation(Outcome.UNAVAILABLE, List.of(), false);
    }

    public boolean settled() {
        return outcome != Outcome.UNAVAILABLE;
    }
}
