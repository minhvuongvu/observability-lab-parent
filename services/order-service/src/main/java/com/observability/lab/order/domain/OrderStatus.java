package com.observability.lab.order.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * States an order can be in, and the moves between them.
 *
 * <p>Keeping the transition table here rather than as scattered {@code if} statements means there is
 * exactly one answer to "can this order be cancelled", and adding a state forces the question to be
 * answered for every existing state.
 *
 * <pre>
 *   PENDING ──► CONFIRMED ──► CANCELLED
 *      │  │                      ▲
 *      │  └──────────────────────┘
 *      └──► REJECTED
 * </pre>
 */
public enum OrderStatus {

    /** Accepted and persisted; stock not yet reserved. */
    PENDING,

    /** Stock reserved. The order will be fulfilled. */
    CONFIRMED,

    /** Stock could not be reserved. Terminal. */
    REJECTED,

    /** Withdrawn, by the customer or by an operator. Terminal. */
    CANCELLED;

    /**
     * Legal moves out of each state.
     *
     * <p>A confirmed order can still be cancelled — stock has been reserved, and cancelling is what
     * releases it. A rejected or cancelled order cannot move at all: reopening one would mean the
     * event other services already reacted to no longer describes reality.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            PENDING, EnumSet.of(CONFIRMED, REJECTED, CANCELLED),
            CONFIRMED, EnumSet.of(CANCELLED),
            REJECTED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    /** Whether this order may move to {@code target}. Staying put is never a transition. */
    public boolean canTransitionTo(OrderStatus target) {
        return target != null && ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /** Whether no further transition is possible from here. */
    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }
}
