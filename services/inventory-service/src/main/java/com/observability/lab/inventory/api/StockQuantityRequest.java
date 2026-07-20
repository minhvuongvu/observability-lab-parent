package com.observability.lab.inventory.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for a quantity movement: receiving, releasing or adjusting.
 *
 * <p>The same shape serves all three because the difference between them is the operation, not the
 * payload. Which one was performed is recorded on the movement.
 *
 * @param quantity  how many units, always positive — direction comes from the endpoint
 * @param reference why it happened, normally a delivery note or an order number. Required, because
 *                  a movement with no reason is a number nobody can later account for.
 */
public record StockQuantityRequest(
        @Positive(message = "quantity must be greater than zero")
        @Max(value = 1_000_000, message = "quantity must be at most 1000000")
        int quantity,

        @NotBlank(message = "reference is required")
        @Size(max = 64, message = "reference must be at most 64 characters")
        String reference) {}
