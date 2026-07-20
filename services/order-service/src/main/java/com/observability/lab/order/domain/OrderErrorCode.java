package com.observability.lab.order.domain;

import com.observability.lab.shared.exception.ErrorCategory;
import com.observability.lab.shared.exception.ErrorCode;

/**
 * Failures this bounded context can report.
 *
 * <p>The {@code ORD-} prefix identifies the owner unambiguously in a log store shared by every
 * service. The digits after it mirror the HTTP class the category maps to, so {@code ORD-4221} reads
 * as "a 4xx from the Order Service" at a glance.
 *
 * <p>Codes are a published contract: clients branch on them. Never reword what a code means and
 * never reuse a retired one.
 */
public enum OrderErrorCode implements ErrorCode {

    EMPTY_ORDER("ORD-4000", ErrorCategory.VALIDATION,
            "An order must contain at least one item."),

    ORDER_NOT_FOUND("ORD-4040", ErrorCategory.NOT_FOUND,
            "The requested order does not exist."),

    DUPLICATE_ORDER_NUMBER("ORD-4090", ErrorCategory.CONFLICT,
            "An order with that number already exists."),

    // Business rules, not errors: the system refusing correctly. Logged at INFO.
    ILLEGAL_STATUS_TRANSITION("ORD-4220", ErrorCategory.BUSINESS_RULE,
            "The order cannot move to that status."),

    ORDER_NOT_DELETABLE("ORD-4221", ErrorCategory.BUSINESS_RULE,
            "Only a cancelled or rejected order can be deleted.");

    private final String code;
    private final ErrorCategory category;
    private final String defaultMessage;

    OrderErrorCode(String code, ErrorCategory category, String defaultMessage) {
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
