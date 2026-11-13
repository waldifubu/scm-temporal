package com.supplychainmanagement.dto.fullfillment;

import org.jspecify.annotations.Nullable;
import com.supplychainmanagement.model.enums.FulfillmentStatus;

public record AvailableOrderItemDto(
        Long orderItemId,
        Long articleNo,
        Integer orderQuantity,
        Integer availableQuantity,
        boolean available,
        @Nullable Long storehouseId,
        FulfillmentStatus fulfillmentStatus
) {
}
