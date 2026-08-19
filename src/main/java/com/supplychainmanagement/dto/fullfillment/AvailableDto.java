package com.supplychainmanagement.dto.fullfillment;

import com.supplychainmanagement.model.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

public record AvailableDto (
        String orderNo,
        OrderStatus status,
        LocalDateTime orderDate,
        List<AvailableOrderItemDto> item,
        String message,
        boolean available
) {
}