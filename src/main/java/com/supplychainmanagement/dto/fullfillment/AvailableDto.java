package com.supplychainmanagement.dto.fullfillment;

import org.jspecify.annotations.Nullable;
import com.supplychainmanagement.model.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

public record AvailableDto (
        String orderNo,
        OrderStatus status,
        LocalDateTime orderDate,
        List<AvailableOrderItemDto> item,
        @Nullable String message,
        boolean available
) {
}