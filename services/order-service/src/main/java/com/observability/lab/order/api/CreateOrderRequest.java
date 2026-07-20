package com.observability.lab.order.api;

import com.observability.lab.order.application.CreateOrderCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body for placing an order.
 *
 * <p>Notably absent: any total. The server computes it from the lines. Accepting a total from the
 * client would mean trusting the client's arithmetic, and trusting the client's honesty.
 *
 * @param customerId who is placing the order
 * @param currency   ISO 4217 code the order is priced in
 * @param items      the lines, at least one
 */
public record CreateOrderRequest(
        @NotBlank(message = "customerId is required")
        @Size(max = 64, message = "customerId must be at most 64 characters")
        String customerId,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a 3-letter ISO 4217 code")
        String currency,

        @NotEmpty(message = "an order must contain at least one item")
        // Bounded so one request cannot become an unbounded transaction. Without a ceiling, a
        // 100,000-line order is a perfectly valid way to hold a database connection for minutes.
        @Size(max = 100, message = "an order may contain at most 100 items")
        // Cascades validation into each element; without it the item constraints never run.
        @Valid
        List<OrderItemRequest> items) {

    /**
     * Translates the wire format into what the use case needs.
     *
     * <p>The boundary between "shaped for JSON" and "shaped for the domain". It exists so a change
     * to either side does not force a change to the other.
     */
    public CreateOrderCommand toCommand() {
        return new CreateOrderCommand(
                customerId,
                currency,
                items.stream()
                        .map(item -> new CreateOrderCommand.Line(
                                item.productSku(), item.quantity(), item.unitPrice()))
                        .toList());
    }
}
