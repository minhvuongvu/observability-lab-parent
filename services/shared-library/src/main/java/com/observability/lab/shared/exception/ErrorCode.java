package com.observability.lab.shared.exception;

/**
 * A stable, machine-readable identifier for one specific failure.
 *
 * <p>Implemented as an interface rather than a single enum so each bounded context can own its own
 * codes without the shared library having to know about them. The Order Service declares an
 * {@code OrderErrorCode} enum, the Inventory Service an {@code InventoryErrorCode}, and both flow
 * through the same exception hierarchy and the same handler.
 *
 * <p>Codes follow {@code <CTX>-<HTTP class><serial>}, for example {@code ORD-4001} or
 * {@code INV-5003}. The context prefix means a code identifies its owner unambiguously in a log
 * store shared by every service.
 *
 * <pre>{@code
 * public enum OrderErrorCode implements ErrorCode {
 *     INSUFFICIENT_STOCK("ORD-4221", ErrorCategory.BUSINESS_RULE, "Not enough stock to fulfil the order.");
 *     // ...
 * }
 * }</pre>
 */
public interface ErrorCode {

    /** The stable identifier clients branch on. Never reword or reuse a published code. */
    String code();

    /** What kind of failure this is, which decides status code and log level. */
    ErrorCategory category();

    /** Message used when the throwing site does not supply a more specific one. */
    String defaultMessage();
}
