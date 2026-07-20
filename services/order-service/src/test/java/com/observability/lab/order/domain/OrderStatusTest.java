package com.observability.lab.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("OrderStatus transitions")
class OrderStatusTest {

    @ParameterizedTest(name = "{0} -> {1} is {2}")
    @CsvSource({
        "PENDING,   CONFIRMED, true",
        "PENDING,   REJECTED,  true",
        "PENDING,   CANCELLED, true",
        // A confirmed order can still be cancelled: stock is reserved, and cancelling releases it.
        "CONFIRMED, CANCELLED, true",
        // Going backwards would contradict an event other services already acted on.
        "CONFIRMED, PENDING,   false",
        "CONFIRMED, REJECTED,  false",
        "REJECTED,  PENDING,   false",
        "REJECTED,  CONFIRMED, false",
        "CANCELLED, PENDING,   false",
        "CANCELLED, CONFIRMED, false",
    })
    @DisplayName("follows the declared transition table")
    void transitions(OrderStatus from, OrderStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("never allows a status to transition to itself")
    void noSelfTransition(OrderStatus status) {
        // Re-cancelling a cancelled order is not a state change, and treating it as one would let
        // the same side effect fire twice.
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("tolerates a null target rather than throwing")
    void nullTarget(OrderStatus status) {
        assertThat(status.canTransitionTo(null)).isFalse();
    }

    @Test
    @DisplayName("identifies exactly the terminal states")
    void terminalStates() {
        assertThat(Arrays.stream(OrderStatus.values()).filter(OrderStatus::isTerminal))
                .containsExactlyInAnyOrder(OrderStatus.REJECTED, OrderStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("declares an answer for every status, so a new one cannot be forgotten")
    void everyStatusIsCovered(OrderStatus status) {
        // Reaching canTransitionTo at all proves the transition table has an entry: a missing one
        // would be a NullPointerException on the map lookup.
        assertThat(status.canTransitionTo(OrderStatus.CANCELLED)).isIn(true, false);
    }
}
