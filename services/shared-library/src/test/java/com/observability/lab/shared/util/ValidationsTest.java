package com.observability.lab.shared.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.observability.lab.shared.api.FieldViolation;
import com.observability.lab.shared.exception.ValidationException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Validations")
class ValidationsTest {

    private static final class OrderCommand {
        @NotBlank(message = "must not be blank")
        private final String customerId;

        @Min(value = 1, message = "must be at least 1")
        private final int quantity;

        private OrderCommand(String customerId, int quantity) {
            this.customerId = customerId;
            this.quantity = quantity;
        }
    }

    @Test
    @DisplayName("accepts a valid object silently")
    void acceptsValid() {
        Validations.validate(new OrderCommand("C-1", 3));
        assertThat(Validations.check(new OrderCommand("C-1", 3))).isEmpty();
    }

    @Test
    @DisplayName("throws a ValidationException carrying every violation")
    void throwsOnInvalid() {
        assertThatThrownBy(() -> Validations.validate(new OrderCommand("  ", 0)))
                .isInstanceOf(ValidationException.class)
                .satisfies(thrown -> assertThat(((ValidationException) thrown).violations())
                        .extracting(FieldViolation::field)
                        .containsExactly("customerId", "quantity"));
    }

    @Test
    @DisplayName("reports violations without throwing, for checking several objects at once")
    void reportsWithoutThrowing() {
        List<FieldViolation> violations = Validations.check(new OrderCommand(null, -5));
        assertThat(violations).hasSize(2);
    }

    @Test
    @DisplayName("orders violations by field so output is stable run to run")
    void stableOrdering() {
        // Bean Validation returns an unordered Set; without sorting, assertions on the message
        // list are flaky in a way that only shows up occasionally in CI.
        List<FieldViolation> first = Validations.check(new OrderCommand("", 0));
        List<FieldViolation> second = Validations.check(new OrderCommand("", 0));
        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(FieldViolation::field).containsExactly("customerId", "quantity");
    }

    @Test
    @DisplayName("reports a null object as a violation rather than throwing NullPointerException")
    void handlesNull() {
        assertThat(Validations.check(null)).hasSize(1);
    }
}
