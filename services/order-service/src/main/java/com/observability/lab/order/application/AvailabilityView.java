package com.observability.lab.order.application;

/**
 * What the Inventory Service says about one product, right now.
 *
 * <p>Explicitly a <em>snapshot</em>, not a promise. Between this answer and the order that follows
 * it, any number of other customers can reserve the same units. Nothing here reserves anything —
 * that only happens when the Inventory Service handles {@code order-created}, and its verdict is the
 * one that counts.
 *
 * @param productSku        the product asked about
 * @param requestedQuantity how many the caller was considering
 * @param availableQuantity how many could be reserved at the moment of the call
 * @param sufficient        whether the request would be satisfiable right now
 * @param known             whether inventory tracks this product at all
 */
public record AvailabilityView(
        String productSku,
        int requestedQuantity,
        int availableQuantity,
        boolean sufficient,
        boolean known) {

    /**
     * Public because both transports build these, and the gRPC adapter lives outside this package.
     *
     * <p>Keeping the constructors here rather than letting each adapter assemble the record itself
     * is what guarantees the two transports produce identical answers: {@code sufficient} is derived
     * in one place, from one comparison, so REST and gRPC cannot disagree about what "enough" means.
     */
    public static AvailabilityView of(String productSku, int requested, int available) {
        return new AvailabilityView(productSku, requested, available, available >= requested, true);
    }

    /**
     * A product inventory has never heard of, or could not be asked about.
     *
     * <p>Reported rather than thrown: asking about a typo'd SKU is a question with an answer, and a
     * caller checking five products should not lose the other four to an exception. It is also the
     * degraded answer when the dependency is unreachable — "cannot tell" is honest, where a
     * confident "out of stock" would not be.
     */
    public static AvailabilityView unknown(String productSku, int requested) {
        return new AvailabilityView(productSku, requested, 0, false, false);
    }
}
