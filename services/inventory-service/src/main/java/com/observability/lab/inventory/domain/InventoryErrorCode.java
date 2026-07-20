package com.observability.lab.inventory.domain;

import com.observability.lab.shared.exception.ErrorCategory;
import com.observability.lab.shared.exception.ErrorCode;

/**
 * Failures this bounded context can report.
 *
 * <p>The {@code INV-} prefix identifies the owner unambiguously in a log store shared by every
 * service, so {@code INV-4220} is recognisable as an inventory rule without knowing the catalogue.
 *
 * <p>{@code INSUFFICIENT_STOCK} is a {@code BUSINESS_RULE}, not an error: refusing to promise units
 * that do not exist is the service working correctly. It surfaces as 422 and is logged at
 * {@code INFO}, so it cannot inflate the error rate every alert is built on.
 */
public enum InventoryErrorCode implements ErrorCode {

    INVALID_QUANTITY("INV-4000", ErrorCategory.VALIDATION,
            "The quantity must be a positive number."),

    STOCK_NOT_FOUND("INV-4040", ErrorCategory.NOT_FOUND,
            "No stock level is recorded for that product."),

    DUPLICATE_SKU("INV-4090", ErrorCategory.CONFLICT,
            "A stock level already exists for that product."),

    INSUFFICIENT_STOCK("INV-4220", ErrorCategory.BUSINESS_RULE,
            "There is not enough available stock to reserve."),

    INSUFFICIENT_RESERVATION("INV-4221", ErrorCategory.BUSINESS_RULE,
            "There is not enough reserved stock to release."),

    STOCK_STILL_RESERVED("INV-4222", ErrorCategory.BUSINESS_RULE,
            "Stock with outstanding reservations cannot be removed.");

    private final String code;
    private final ErrorCategory category;
    private final String defaultMessage;

    InventoryErrorCode(String code, ErrorCategory category, String defaultMessage) {
        this.code = code;
        this.category = category;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public ErrorCategory category() {
        return category;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
