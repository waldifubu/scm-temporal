package com.supplychainmanagement.dto.fullfillment;

import com.supplychainmanagement.model.enums.FulfillmentStatus;

public record AvailableOrderItemDto(
        Long orderItemId,
        Long articleNo,
        Integer orderQuantity,
        Integer availableQuantity,
        boolean available,
        Long storehouseId,
        FulfillmentStatus fulfillmentStatus
) {
}
