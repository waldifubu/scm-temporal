package com.supplychainmanagement.event;

import com.supplychainmanagement.model.enums.OrderStatus;

public record OrderStatusChangedEvent(
        Long orderId,
        Long userId,
        OrderStatus previousStatus,
        OrderStatus newStatus
) {
}
