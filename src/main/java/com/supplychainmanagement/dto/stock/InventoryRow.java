package com.supplychainmanagement.dto.stock;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What the warehouse holds of one article, all storehouses added up: one row per SKU of
 * {@code GET /stock/inventory}.
 *
 * @param name      the product behind the SKU, for a client to show on hover; {@code null} for stock whose SKU
 *                  no product carries
 * @param available {@code onHand} minus {@code reserved}, worked out here - the sum of the storehouses' own
 *                  available quantities, which is the same number
 * @param updatedAt the latest change on any of the SKU's rows
 */
public record InventoryRow(
        UUID sku,
        String name,
        long onHand,
        long reserved,
        long available,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime updatedAt
) {
}
