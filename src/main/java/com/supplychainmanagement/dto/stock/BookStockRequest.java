package com.supplychainmanagement.dto.stock;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the warehouse sends to book delivered component requests into stock: the requests, and the
 * storehouse the goods arrived in. The same storehouse for all of them - one delivery, one dock.
 * <p>
 * The storehouse is optional: without it each request is booked into the storehouse that already holds most of its
 * part (see {@code StockService.suggestReceivingStorehouse}).
 * <p>
 * Not {@link AddStockRequest}: that names a SKU and a quantity, which is what {@code POST /stock/add}
 * books by hand. Here the SKU and the quantity are the requests' own, so the client cannot book more
 * than was asked for or a part that was never ordered.
 */
public record BookStockRequest(
        @NotEmpty(message = "requestIds must not be empty")
        List<@NotNull(message = "a request id must not be null") Long> requestIds,
        @Nullable Long storehouseId
) {
}
