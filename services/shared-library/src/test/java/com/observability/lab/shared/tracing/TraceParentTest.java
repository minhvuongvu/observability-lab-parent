package com.observability.lab.shared.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("W3C traceparent parsing")
class TraceParentTest {

    private static final String VALID = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Nested
    @DisplayName("accepts")
    class Accepts {

        @Test
        @DisplayName("a well-formed header, exposing each component")
        void wellFormedHeader() {
            assertThat(TraceParent.parse(VALID)).hasValueSatisfying(parsed -> {
                assertThat(parsed.version()).isEqualTo("00");
                assertThat(parsed.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
                assertThat(parsed.parentId()).isEqualTo("00f067aa0ba902b7");
                assertThat(parsed.traceFlags()).isEqualTo("01");
            });
        }

        @Test
        @DisplayName("upper-case hex, normalising it to lower case")
        void upperCaseIsNormalised() {
            assertThat(TraceParent.parse(VALID.toUpperCase()))
                    .hasValueSatisfying(parsed ->
                            assertThat(parsed.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736"));
        }

        @Test
        @DisplayName("round-tripping back to the wire format")
        void roundTrips() {
            assertThat(TraceParent.parse(VALID).orElseThrow().value()).isEqualTo(VALID);
        }
    }

    @Nested
    @DisplayName("rejects")
    class Rejects {

        @ParameterizedTest(name = "[{index}] {0}")
        @NullSource
        @ValueSource(strings = {
            "",
            "not-a-traceparent",
            // one character short
            "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01",
            // all-zero trace id: structurally valid, semantically meaningless
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
            // all-zero parent span id
            "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
            // version ff is reserved and must never be accepted
            "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            // 'g' is not a hex digit
            "00-4bf92f3577b34da6a3ce929d0e0e473g-00f067aa0ba902b7-01",
        })
        @DisplayName("malformed input, without throwing")
        void malformedInput(String header) {
            assertThat(TraceParent.parse(header)).isEmpty();
        }
    }

    @Nested
    @DisplayName("sampling flag")
    class Sampling {

        @Test
        @DisplayName("is read from the low bit of the flags byte")
        void readsLowBit() {
            assertThat(TraceParent.parse(VALID).orElseThrow().sampled()).isTrue();
            assertThat(TraceParent.parse(VALID.substring(0, VALID.length() - 2) + "00")
                    .orElseThrow()
                    .sampled())
                    .isFalse();
            // Other bits set, sampled bit clear.
            assertThat(TraceParent.parse(VALID.substring(0, VALID.length() - 2) + "fe")
                    .orElseThrow()
                    .sampled())
                    .isFalse();
        }
    }
}
