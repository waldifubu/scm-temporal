package com.supplychainmanagement.dto.shipping;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PackItem(
        @NotNull(message = "orderItemId is required")
        Long orderItemId,
        // A package item of 0 is never valid - see PackingServiceImpl.requireValidItems.
        @NotNull(message = "qty is required")
        @Min(value = 1, message = "qty must be at least 1")
        Integer qty
) {}