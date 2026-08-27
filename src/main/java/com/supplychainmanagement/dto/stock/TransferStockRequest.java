package com.supplychainmanagement.dto.stock;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record TransferStockRequest(
        @NotNull UUID sku,
        @NotNull Long storehouseFrom,
        @NotNull Long storehouseTo,
        @NotNull @Positive Integer qty
) {
}
