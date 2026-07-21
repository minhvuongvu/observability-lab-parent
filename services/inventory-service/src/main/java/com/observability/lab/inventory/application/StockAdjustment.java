package com.observability.lab.inventory.application;

/**
 * One correction from a bulk reconciliation.
 *
 * <p>A signed delta rather than an absolute quantity. A reconciliation job runs against a warehouse
 * count taken minutes earlier, and setting an absolute value would silently discard every movement
 * that happened in between; a delta composes with them.
 *
 * @param productSku    the product to correct
 * @param quantityDelta signed change to the available quantity; negative releases nothing, it
 *                      removes units that were never there
 * @param reference     why, for the movement trail — a stock-take id, a damage report
 */
public record StockAdjustment(String productSku, int quantityDelta, String reference) {}
