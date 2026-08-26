package com.supplychainmanagement.dto.reservation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReserveItem(

        @NotNull
        UUID sku,

        @Min(1)
        Integer quantity,

        Long storehouseId

        /*
        Candidate criteria for picking a storehouse:
        available stock
        distance to the customer
        delivery time
        priority
        cost
         */
) {
}