package com.supplychainmanagement.dto.reservation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReserveItem(

        @NotNull
        UUID sku,

        @Min(1)
        int quantity,

        Long storehouseId

        /*
        Verfügbarer Bestand:
        Entfernung zum Kunden
        Lieferzeit
        Priorität
        Kosten
         */
) {
}