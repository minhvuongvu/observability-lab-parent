package com.observability.lab.shared.api;

import java.util.List;

/**
 * A page of results, in a shape that does not leak the persistence framework.
 *
 * <p>Returning Spring Data's {@code Page} directly is tempting and wrong: its JSON is unstable
 * across versions, it carries {@code Pageable} internals no client needs, and it ties the public API
 * contract to the repository technology. This record is the boundary.
 *
 * @param content       the items on this page
 * @param page          zero-based page number
 * @param size          requested page size
 * @param totalElements total matching items across all pages
 * @param totalPages    total number of pages
 * @param first         whether this is the first page
 * @param last          whether this is the last page
 * @param <T>           item type
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public PageResponse {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /**
     * Builds a page, deriving the totals that callers otherwise get wrong.
     *
     * @param content       items on this page
     * @param page          zero-based page number
     * @param size          page size; must be positive
     * @param totalElements total matching items
     */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be positive, was " + size);
        }
        // Ceiling division without floating point, which would round wrongly at the boundary.
        int totalPages = (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                page == 0,
                // An empty result set has zero pages, and page zero is then both first and last.
                totalPages == 0 || page >= totalPages - 1);
    }

    /** An empty page, for a query that matched nothing. */
    public static <T> PageResponse<T> empty(int page, int size) {
        return of(List.of(), page, size, 0);
    }
}
