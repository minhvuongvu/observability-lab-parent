package com.observability.lab.inventory.application;

import java.util.List;

/**
 * What one batch of a bulk reconciliation did.
 *
 * <p>Counts and reasons, never the adjustments themselves. A summary that echoed ten thousand
 * corrections back would be a response whose size is proportional to the request — which defeats
 * the point of streaming the request in the first place.
 *
 * @param applied    corrections that took effect
 * @param rejections why each refused line was refused, one entry per refusal
 */
public record BulkAdjustmentResult(int applied, List<String> rejections) {

    public BulkAdjustmentResult {
        rejections = rejections == null ? List.of() : List.copyOf(rejections);
    }

    public int rejected() {
        return rejections.size();
    }

    /** Adds another batch's outcome to this one, for a summary covering a whole job. */
    public BulkAdjustmentResult plus(BulkAdjustmentResult other) {
        List<String> combined = new java.util.ArrayList<>(rejections);
        combined.addAll(other.rejections);
        return new BulkAdjustmentResult(applied + other.applied, combined);
    }

    public static BulkAdjustmentResult empty() {
        return new BulkAdjustmentResult(0, List.of());
    }
}
