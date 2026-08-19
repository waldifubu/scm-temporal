package com.supplychainmanagement.dto.order;

public record OrderItemDto(
        Long id,
        Integer quantity,
        String productName
) {
}
