package com.supplychainmanagement.dto.stock;

import com.supplychainmanagement.entity.Stock;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One stock row as the API answers it.
 * <p>
 * The endpoints handed out the {@code Stock} entity, which carries a LAZY {@code Storehouse}: the
 * response writer touched it and open-in-view resolved it, one query per row. Here the storehouse is
 * its id and name, read while the transaction is still open.
 *
 * @param available what can still be promised - {@code onHand} minus {@code reserved}. Computed on
 *                  the entity; spelling it out saves every caller from doing the subtraction and
 *                  getting it wrong
 */
public record StockResponse(
        Long id,
        UUID sku,
        int onHand,
        int reserved,
        int available,
        Long storehouseId,
        String storehouseName,
        LocalDateTime updatedAt
) {

    /** Mapped while the session is open - {@code storehouse} is LAZY. */
    public static StockResponse from(Stock stock) {
        var storehouse = stock.getStorehouse();

        return new StockResponse(
                stock.getId(),
                stock.getSku(),
                stock.getOnHand(),
                stock.getReserved(),
                stock.getAvailable(),
                storehouse != null ? storehouse.getId() : null,
                storehouse != null ? storehouse.getName() : null,
                stock.getUpdatedAt()
        );
    }
}
