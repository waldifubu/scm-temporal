package com.supplychainmanagement.dto.fullfillment;

import java.util.UUID;

public record ProductionResultDto(
        String productName,
        UUID sku,
        Long storehouseId,
        boolean produced,
        String message
) {
}
