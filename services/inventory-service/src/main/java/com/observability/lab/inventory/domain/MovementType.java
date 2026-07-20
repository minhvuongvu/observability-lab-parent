package com.observability.lab.inventory.domain;

/**
 * Why a stock level changed.
 *
 * <p>Every change is recorded with one of these, which is what turns "the number is wrong" from an
 * argument into a query. Without the movement trail, a stock level is a value with no history and no
 * way to establish which of several concurrent effects produced it.
 */
public enum MovementType {

    /** Units arrived and became available. */
    RECEIPT,

    /** Units were promised to an order: available goes down, reserved goes up. */
    RESERVATION,

    /** A reservation was undone, normally because the order was cancelled or rejected. */
    RELEASE,

    /** A correction applied by an operator, for example after a stock count. */
    ADJUSTMENT
}
