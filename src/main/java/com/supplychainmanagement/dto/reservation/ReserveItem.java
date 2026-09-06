package com.supplychainmanagement.dto.reservation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReserveItem(

        /*
        The order line this item belongs to. Carried along so the reservation can be linked to it
        without looking it back up from (orderId, sku).
         */
        @NotNull
        Long orderItemId,

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