package com.supplychainmanagement.dto.reservation;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ReserveRequest(

        @NotBlank
        String orderId,

        List<ReserveItem> items
) {
}