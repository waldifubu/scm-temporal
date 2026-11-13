package com.supplychainmanagement.dto.order;

import org.jspecify.annotations.Nullable;
import com.supplychainmanagement.model.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailsDto(
        Long orderNo,
        BigDecimal total,
        OrderStatus status,
        @Nullable LocalDate dueDate,
        LocalDateTime created,
        String customerName,
        List<OrderItemDto> items
) {
}
