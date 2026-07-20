package com.observability.lab.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Masking")
class MaskingTest {

    @Nested
    @DisplayName("secret")
    class Secrets {

        @ParameterizedTest
        @ValueSource(strings = {"1234", "hunter2", "a-very-long-bearer-token-value-here"})
        @DisplayName("reveals nothing, not even the length")
        void revealsNothing(String value) {
            // Length narrows a brute-force search: a 4-digit PIN and a 32-character token must be
            // indistinguishable once masked.
            assertThat(Masking.secret(value)).isEqualTo("****");
        }

        @Test
        @DisplayName("passes null through rather than throwing inside a log statement")
        void nullSafe() {
            assertThat(Masking.secret(null)).isNull();
        }
    }

    @Nested
    @DisplayName("tail")
    class Tail {

        @Test
        @DisplayName("keeps the trailing characters a human uses to recognise the value")
        void keepsTail() {
            assertThat(Masking.tail("4111111111111234", 4)).isEqualTo("****1234");
        }

        @Test
        @DisplayName("redacts entirely when the value is too short to mask meaningfully")
        void redactsShortValues() {
            // Revealing 4 of 4 characters would be no masking at all.
            assertThat(Masking.tail("1234", 4)).isEqualTo("****");
            assertThat(Masking.tail("12", 4)).isEqualTo("****");
        }

        @Test
        @DisplayName("redacts entirely when asked to reveal nothing")
        void redactsOnZeroVisible() {
            assertThat(Masking.tail("4111111111111234", 0)).isEqualTo("****");
        }
    }

    @Nested
    @DisplayName("email")
    class Email {

        @Test
        @DisplayName("keeps the domain and the first character of the local part")
        void masksLocalPart() {
            assertThat(Masking.email("jane.doe@example.com")).isEqualTo("j****@example.com");
        }

        @ParameterizedTest
        @ValueSource(strings = {"not-an-email", "@example.com", "jane@", "@"})
        @DisplayName("redacts anything that is not a usable address rather than guessing")
        void redactsMalformed(String value) {
            assertThat(Masking.email(value)).isEqualTo("****");
        }

        @Test
        @DisplayName("passes null through")
        void nullSafe() {
            assertThat(Masking.email(null)).isNull();
        }
    }
}
