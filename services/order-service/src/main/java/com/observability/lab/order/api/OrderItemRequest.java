package com.observability.lab.order.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * One requested order line, as it arrives over HTTP.
 *
 * <p>Every bound is the same bound the database column enforces. Rejecting an oversized SKU here
 * returns a clear field-level message; letting it through returns a 500 from a truncated-value
 * error several layers down, with a stack trace and no indication of which field was at fault.
 *
 * @param productSku what to buy
 * @param quantity   how many
 * @param unitPrice  agreed price per unit
 */
public record OrderItemRequest(
        @NotBlank(message = "productSku is required")
        @Size(max = 64, message = "productSku must be at most 64 characters")
        String productSku,

        @Positive(message = "quantity must be greater than zero")
        // An upper bound as well as a lower one: without it a single request can ask for two
        // billion units and the total overflows the column it has to fit in.
        @Max(value = 10_000, message = "quantity must be at most 10000")
        int quantity,

        @NotNull(message = "unitPrice is required")
        @DecimalMin(value = "0.00", message = "unitPrice must not be negative")
        @Digits(integer = 17, fraction = 2,
                message = "unitPrice must have at most 17 integer digits and 2 decimals")
        BigDecimal unitPrice) {}
