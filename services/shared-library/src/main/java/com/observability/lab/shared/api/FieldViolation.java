package com.observability.lab.shared.api;

/**
 * One field-level validation failure.
 *
 * <p>Deliberately carries no rejected value. Echoing the input back is convenient right up to the
 * request where the invalid field was a password, a token or a card number — at which point the
 * secret has been written to the response body, the access log and every log aggregator downstream.
 * The field name and the reason are enough for a caller to fix their request.
 *
 * @param field   path of the offending field, for example {@code items[0].quantity}
 * @param message human-readable reason the value was rejected
 */
public record FieldViolation(String field, String message) {}
