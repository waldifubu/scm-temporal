package com.supplychainmanagement.dto.reservation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReserveItem(
        @NotNull
        Long orderItemId,

        @NotNull
        UUID sku,

        @Min(1)
        Integer quantity,

        @NotNull
        Long storehouseId
) {
}