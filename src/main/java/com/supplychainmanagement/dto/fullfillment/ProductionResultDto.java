package com.supplychainmanagement.dto.fullfillment;

import org.jspecify.annotations.Nullable;
import java.util.UUID;

public record ProductionResultDto(
        String productName,
        UUID sku,
        @Nullable Long storehouseId,
        boolean produced,
        @Nullable String message
) {
}
