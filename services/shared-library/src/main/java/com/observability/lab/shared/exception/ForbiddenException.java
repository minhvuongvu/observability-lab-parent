package com.observability.lab.shared.exception;

import java.io.Serial;

/**
 * The credentials verified but do not grant access to this resource. Renders as 403.
 *
 * <p>Named {@code Forbidden} rather than {@code AccessDeniedException} to avoid colliding with
 * Spring Security's type of that name.
 */
public class ForbiddenException extends BaseException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ForbiddenException() {
        super(PlatformErrorCode.ACCESS_DENIED);
    }

    public ForbiddenException(String message) {
        super(PlatformErrorCode.ACCESS_DENIED, message);
    }

    /**
     * Builds the conventional message for a missing privilege.
     *
     * @param action   what was attempted, for example {@code cancel}
     * @param resource what it was attempted on, for example {@code order}
     */
    public static ForbiddenException of(String action, String resource) {
        return new ForbiddenException("Not permitted to " + action + " this " + resource + ".");
    }
}
