package com.observability.lab.shared.api;

/**
 * The envelope every endpoint returns, on success and on failure alike.
 *
 * <p>One shape for both outcomes is a deliberate trade. It costs a level of nesting on the happy
 * path; it buys a client that parses one structure instead of two, and a guarantee that the trace
 * id is present on the responses where it matters most — the failures.
 *
 * <p>Null members are omitted from JSON by {@code spring.jackson.default-property-inclusion}, so a
 * success carries no {@code error} key and a failure carries no {@code data} key.
 *
 * <pre>{@code
 * {
 *   "success": true,
 *   "data": { "orderId": "..." },
 *   "meta": { "timestamp": "...", "requestId": "...", "traceId": "..." }
 * }
 * }</pre>
 *
 * @param success whether the request succeeded; the discriminator a client should branch on
 * @param data    the payload, present only on success
 * @param error   the failure detail, present only on failure
 * @param meta    identifying metadata, always present
 * @param <T>     payload type
 */
public record ApiResponse<T>(boolean success, T data, ApiError error, ResponseMeta meta) {

    /** A successful response carrying a payload. */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, ResponseMeta.current());
    }

    /**
     * A successful response with no payload, for operations whose result is the status code.
     *
     * <p>Not named {@code success()}: the {@code success} record component already generates an
     * accessor of that exact signature, and a record cannot declare both.
     */
    public static <T> ApiResponse<T> successNoContent() {
        return new ApiResponse<>(true, null, null, ResponseMeta.current());
    }

    /** A failed response. */
    public static <T> ApiResponse<T> failure(ApiError error) {
        return new ApiResponse<>(false, null, error, ResponseMeta.current());
    }

    /** A failed response, built from the parts of an error. */
    public static <T> ApiResponse<T> failure(String code, String message) {
        return failure(ApiError.of(code, message));
    }
}
