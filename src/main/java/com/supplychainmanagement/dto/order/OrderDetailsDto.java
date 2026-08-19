package com.supplychainmanagement.dto.order;

import com.supplychainmanagement.model.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailsDto(
        Long orderNo,
        BigDecimal total,
        OrderStatus status,
        LocalDate dueDate,
        LocalDateTime orderDate,
        String customerName,
        List<OrderItemDto> items
) {
}
