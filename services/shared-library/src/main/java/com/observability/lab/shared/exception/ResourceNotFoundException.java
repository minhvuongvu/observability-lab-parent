package com.observability.lab.shared.exception;

import java.io.Serial;

/**
 * The addressed resource does not exist.
 *
 * <p>Note this is a {@code WARN}, not an {@code ERROR}: a 404 is the API answering a question
 * correctly. It is logged rather than ignored because a sudden rise in 404s usually means a client
 * is following stale links or a route has moved.
 */
public class ResourceNotFoundException extends BaseException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String message) {
        super(PlatformErrorCode.RESOURCE_NOT_FOUND, message);
    }

    public ResourceNotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * Builds the conventional message for a resource looked up by identifier.
     *
     * <p>The identifier is included because it is the thing that makes the log line actionable, and
     * an identifier that was already sent in the URL is not a new disclosure.
     *
     * @param resourceType human-readable type, for example {@code Order}
     * @param identifier   the identifier that was not found
     */
    public static ResourceNotFoundException of(String resourceType, Object identifier) {
        return new ResourceNotFoundException(resourceType + " '" + identifier + "' was not found.");
    }
}
