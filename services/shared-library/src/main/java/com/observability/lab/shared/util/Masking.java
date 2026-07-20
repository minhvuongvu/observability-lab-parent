package com.observability.lab.shared.util;

/**
 * Redacts sensitive values before they reach a log line.
 *
 * <p>Centralised because the alternative is each service inventing its own masking, inconsistently,
 * and getting the edge cases wrong. A log store is the least controlled place a secret can end up:
 * replicated, indexed, retained for weeks and readable by everyone with a dashboard.
 *
 * <p>All methods are null-safe and never throw. A masking helper that can fail turns a log statement
 * into a source of exceptions, which is a worse outcome than the disclosure it was preventing.
 */
public final class Masking {

    private static final String REDACTED = "****";

    private Masking() {
        throw new AssertionError("No instances.");
    }

    /**
     * Fully redacts a secret, revealing nothing — not even its length.
     *
     * <p>Length is information: it distinguishes a 4-digit PIN from a 32-character token and
     * narrows a brute-force search. The output is a fixed string regardless of input.
     *
     * @return {@code "****"}, or {@code null} when the input was null
     */
    public static String secret(String value) {
        return value == null ? null : REDACTED;
    }

    /**
     * Keeps the last few characters and masks the rest, for values a human needs to recognise —
     * a card number or an account they are being asked to confirm.
     *
     * <p>When the value is too short to mask meaningfully it is redacted entirely rather than
     * partially revealed.
     *
     * @param value   value to mask
     * @param visible how many trailing characters to keep
     */
    public static String tail(String value, int visible) {
        if (value == null) {
            return null;
        }
        if (visible <= 0 || value.length() <= visible) {
            return REDACTED;
        }
        return REDACTED + value.substring(value.length() - visible);
    }

    /**
     * Masks the local part of an email while keeping the domain.
     *
     * <p>The domain is usually the part worth having in a log — it identifies the tenant or the
     * provider — while the local part identifies the person.
     *
     * <p>{@code jane.doe@example.com} becomes {@code j****@example.com}. A value that is not an
     * email is redacted entirely rather than guessed at.
     */
    public static String email(String value) {
        if (value == null) {
            return null;
        }
        int at = value.indexOf('@');
        // A leading '@' leaves no local part to keep, and no '@' means this is not an address.
        if (at <= 0 || at == value.length() - 1) {
            return REDACTED;
        }
        return value.charAt(0) + REDACTED + value.substring(at);
    }
}
