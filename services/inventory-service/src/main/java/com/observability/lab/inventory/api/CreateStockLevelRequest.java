package com.observability.lab.inventory.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request body for starting to track a product.
 *
 * @param productSku      the product
 * @param initialQuantity units on hand at the outset; zero is legitimate for a product that is
 *                        being set up before its first delivery arrives
 */
public record CreateStockLevelRequest(
        @NotBlank(message = "productSku is required")
        @Size(max = 64, message = "productSku must be at most 64 characters")
        String productSku,

        @PositiveOrZero(message = "initialQuantity must not be negative")
        // Bounded on both sides: without a ceiling a single request can set a quantity that
        // overflows the NUMBER(10) column it has to fit in.
        @Max(value = 1_000_000, message = "initialQuantity must be at most 1000000")
        int initialQuantity) {}
