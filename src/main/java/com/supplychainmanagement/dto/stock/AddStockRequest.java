package com.supplychainmanagement.dto.stock;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * What a client sends to book stock onto a (sku, storehouse) row.
 * <p>
 * The endpoint took a {@code Map<String, String>}: no validation, and a missing {@code sku} threw an
 * NPE inside {@code params.get("sku").equals("new")} - a 500 for a request that was simply
 * incomplete. The same body also accepted the literal string {@code "new"}, which made the
 * controller invent a random UUID and book stock onto a SKU no product has and nothing can find
 * again. A SKU comes into being with the article it identifies, not with a booking, so there is no
 * field for that here.
 */
public record AddStockRequest(
        @NotNull(message = "sku is required") UUID sku,
        @NotNull(message = "storehouseId is required") Long storehouseId,
        @NotNull(message = "qty is required")
        @Positive(message = "qty has to be at least 1") Integer qty
) {
}
