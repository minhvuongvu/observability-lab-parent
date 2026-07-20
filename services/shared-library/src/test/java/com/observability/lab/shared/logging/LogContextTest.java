package com.observability.lab.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("LogContext")
class LogContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("adds a field for the duration of the scope and removes it afterwards")
    void addsAndRemoves() {
        try (LogContext ignored = LogContext.with("order_id", "A-1")) {
            assertThat(MDC.get("order_id")).isEqualTo("A-1");
        }
        assertThat(MDC.get("order_id")).isNull();
    }

    @Test
    @DisplayName("carries several fields at once")
    void carriesSeveralFields() {
        try (LogContext ignored = LogContext.with(Map.of("order_id", "A-1", "customer_id", "C-9"))) {
            assertThat(MDC.get("order_id")).isEqualTo("A-1");
            assertThat(MDC.get("customer_id")).isEqualTo("C-9");
        }
        assertThat(MDC.get("order_id")).isNull();
        assertThat(MDC.get("customer_id")).isNull();
    }

    @Test
    @DisplayName("restores the previous value when scopes nest")
    void nestedScopesRestore() {
        try (LogContext ignored = LogContext.with("stage", "outer")) {
            assertThat(MDC.get("stage")).isEqualTo("outer");

            try (LogContext alsoIgnored = LogContext.with("stage", "inner")) {
                assertThat(MDC.get("stage")).isEqualTo("inner");
            }

            // Without this the inner scope would erase a field the outer scope still needs.
            assertThat(MDC.get("stage")).isEqualTo("outer");
        }
        assertThat(MDC.get("stage")).isNull();
    }

    @Test
    @DisplayName("restores the original value when the same key is set twice in one scope")
    void repeatedKeyInOneScope() {
        MDC.put("stage", "original");
        try (LogContext scope = LogContext.with("stage", "first")) {
            scope.and("stage", "second");
            assertThat(MDC.get("stage")).isEqualTo("second");
        }
        assertThat(MDC.get("stage")).isEqualTo("original");
    }

    @Test
    @DisplayName("cleans up when the guarded block throws")
    void cleansUpOnException() {
        assertThatThrownBy(() -> {
                    try (LogContext ignored = LogContext.with("order_id", "A-1")) {
                        throw new IllegalStateException("boom");
                    }
                })
                .isInstanceOf(IllegalStateException.class);

        // try-with-resources guarantees this; the test pins the guarantee so a refactor to a
        // manual put/remove pair cannot quietly lose it.
        assertThat(MDC.get("order_id")).isNull();
    }
}
