package com.observability.lab.shared.api;

import java.util.List;

/**
 * The error half of {@link ApiResponse}.
 *
 * @param code       stable, machine-readable identifier such as {@code ORD-4001}. Callers branch on
 *                   this; the message is for humans and may be reworded at any time.
 * @param message    human-readable summary, safe to show to the caller
 * @param violations field-level detail for validation failures, {@code null} otherwise
 */
public record ApiError(String code, String message, List<FieldViolation> violations) {

    public ApiError {
        // Normalised at construction so consumers never have to distinguish "no violations" from
        // "an empty list", and so the field is omitted from JSON rather than serialised as [].
        if (violations != null && violations.isEmpty()) {
            violations = null;
        } else if (violations != null) {
            violations = List.copyOf(violations);
        }
    }

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }

    public static ApiError of(String code, String message, List<FieldViolation> violations) {
        return new ApiError(code, message, violations);
    }
}
