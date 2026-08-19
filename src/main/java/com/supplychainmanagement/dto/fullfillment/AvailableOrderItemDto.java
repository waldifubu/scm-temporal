package com.supplychainmanagement.dto.fullfillment;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Storehouse;

public record AvailableOrderItemDto(
        Long articleNo,
        Integer orderQuantity,
        Integer availableQuantity,
        boolean available,
        Long storehouseId
) {
}
