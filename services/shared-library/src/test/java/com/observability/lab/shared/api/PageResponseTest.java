package com.observability.lab.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("PageResponse")
class PageResponseTest {

    @ParameterizedTest(name = "{0} items at size {1} spans {2} pages")
    @CsvSource({
        // The boundary cases are the ones that get written wrong by hand.
        "0,  10, 0",
        "1,  10, 1",
        "10, 10, 1",
        "11, 10, 2",
        "20, 10, 2",
        "21, 10, 3",
        "1,  1,  1",
    })
    @DisplayName("derives the page count by ceiling division")
    void derivesTotalPages(long totalElements, int size, int expectedPages) {
        assertThat(PageResponse.of(List.of(), 0, size, totalElements).totalPages())
                .isEqualTo(expectedPages);
    }

    @Test
    @DisplayName("marks the first and last page correctly across a multi-page result")
    void marksBoundaries() {
        assertThat(PageResponse.of(List.of("a"), 0, 10, 25))
                .satisfies(page -> {
                    assertThat(page.first()).isTrue();
                    assertThat(page.last()).isFalse();
                });
        assertThat(PageResponse.of(List.of("a"), 1, 10, 25))
                .satisfies(page -> {
                    assertThat(page.first()).isFalse();
                    assertThat(page.last()).isFalse();
                });
        assertThat(PageResponse.of(List.of("a"), 2, 10, 25))
                .satisfies(page -> {
                    assertThat(page.first()).isFalse();
                    assertThat(page.last()).isTrue();
                });
    }

    @Test
    @DisplayName("treats an empty result as both the first and the last page")
    void emptyResultIsFirstAndLast() {
        PageResponse<String> page = PageResponse.empty(0, 10);
        assertThat(page.content()).isEmpty();
        assertThat(page.totalPages()).isZero();
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isTrue();
    }

    @Test
    @DisplayName("copies the content so the caller cannot mutate a returned page")
    void contentIsDefensivelyCopied() {
        List<String> mutable = new java.util.ArrayList<>(List.of("a"));
        PageResponse<String> page = PageResponse.of(mutable, 0, 10, 1);
        mutable.add("b");
        assertThat(page.content()).containsExactly("a");
    }

    @Test
    @DisplayName("rejects a non-positive page size rather than dividing by zero")
    void rejectsInvalidSize() {
        assertThatIllegalArgumentException().isThrownBy(() -> PageResponse.of(List.of(), 0, 0, 5));
    }
}
