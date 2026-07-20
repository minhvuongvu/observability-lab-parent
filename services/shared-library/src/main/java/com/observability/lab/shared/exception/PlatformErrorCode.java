package com.observability.lab.shared.exception;

/**
 * Error codes owned by the platform rather than by any bounded context.
 *
 * <p>These cover failures that happen before or around business logic: a body that will not parse,
 * a missing token, an unreachable dependency. Business failures get service-specific codes.
 *
 * <p>The {@code PLT-} prefix makes the origin obvious in a shared log store.
 */
public enum PlatformErrorCode implements ErrorCode {

    VALIDATION_FAILED("PLT-4000", ErrorCategory.VALIDATION, "The request failed validation."),
    MALFORMED_REQUEST("PLT-4001", ErrorCategory.VALIDATION, "The request body could not be read."),
    TYPE_MISMATCH("PLT-4002", ErrorCategory.VALIDATION, "A parameter has the wrong type."),
    MISSING_PARAMETER("PLT-4003", ErrorCategory.VALIDATION, "A required parameter is missing."),

    UNAUTHENTICATED("PLT-4010", ErrorCategory.AUTHENTICATION, "Authentication is required."),
    ACCESS_DENIED("PLT-4030", ErrorCategory.AUTHORIZATION, "Access to this resource is denied."),

    RESOURCE_NOT_FOUND("PLT-4040", ErrorCategory.NOT_FOUND, "The requested resource does not exist."),
    METHOD_NOT_SUPPORTED("PLT-4050", ErrorCategory.VALIDATION, "The HTTP method is not supported here."),
    MEDIA_TYPE_NOT_SUPPORTED("PLT-4150", ErrorCategory.VALIDATION, "The media type is not supported."),

    CONFLICT("PLT-4090", ErrorCategory.CONFLICT, "The request conflicts with the current state."),

    UPSTREAM_FAILED("PLT-5020", ErrorCategory.INTEGRATION, "A downstream dependency failed."),
    UPSTREAM_TIMEOUT("PLT-5040", ErrorCategory.TIMEOUT, "A downstream dependency did not respond in time."),

    INTERNAL_ERROR("PLT-5000", ErrorCategory.TECHNICAL, "An unexpected error occurred.");

    private final String code;
    private final ErrorCategory category;
    private final String defaultMessage;

    PlatformErrorCode(String code, ErrorCategory category, String defaultMessage) {
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
