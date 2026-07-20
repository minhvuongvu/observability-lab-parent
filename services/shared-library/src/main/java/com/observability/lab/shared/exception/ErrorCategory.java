package com.observability.lab.shared.exception;

import org.slf4j.event.Level;

/**
 * What kind of failure occurred, independent of how it is rendered over HTTP.
 *
 * <p>The category is the single input to two decisions that are usually made ad hoc and
 * inconsistently: which status code the caller sees, and at which level the failure is logged. The
 * HTTP mapping lives in the web layer because it is a web concern; the log level lives here because
 * it is not.
 *
 * <p><strong>Why the log level matters.</strong> "Insufficient stock" is the system working
 * correctly and belongs at {@code INFO}. Logging it at {@code ERROR} inflates the error rate every
 * alert is built on, and trains whoever is on call to ignore the level that should wake them up. A
 * category that is the caller's fault is a {@code WARN} at most; only a fault on this side of the
 * boundary is an {@code ERROR}.
 *
 * @see <a href="../../../../../../../../../docs/SystemDesign.md">docs/SystemDesign.md, section 6.1</a>
 */
public enum ErrorCategory {

    /** The request is malformed. The caller must change something. */
    VALIDATION(Level.WARN),

    /** No credentials, or credentials that do not verify. */
    AUTHENTICATION(Level.WARN),

    /** Valid credentials, insufficient privileges. */
    AUTHORIZATION(Level.WARN),

    /** The addressed resource does not exist. */
    NOT_FOUND(Level.WARN),

    /** The request conflicts with current state, such as a duplicate or a stale update. */
    CONFLICT(Level.INFO),

    /** A business rule was correctly enforced. This is the system working, not failing. */
    BUSINESS_RULE(Level.INFO),

    /** A downstream dependency failed. */
    INTEGRATION(Level.ERROR),

    /** A downstream dependency did not answer in time. */
    TIMEOUT(Level.ERROR),

    /** Anything unexpected. Always logged with a stack trace. */
    TECHNICAL(Level.ERROR);

    private final Level logLevel;

    ErrorCategory(Level logLevel) {
        this.logLevel = logLevel;
    }

    /** The level failures in this category are logged at. */
    public Level logLevel() {
        return logLevel;
    }

    /**
     * Whether the fault lies on this side of the boundary.
     *
     * <p>Drives whether a stack trace is logged. A caller sending an invalid field does not need
     * fifty frames of servlet plumbing in the log; a failing dependency does.
     */
    public boolean isServerFault() {
        return this == INTEGRATION || this == TIMEOUT || this == TECHNICAL;
    }
}
