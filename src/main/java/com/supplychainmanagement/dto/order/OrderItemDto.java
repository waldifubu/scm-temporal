package com.supplychainmanagement.dto.order;

import com.supplychainmanagement.model.enums.FulfillmentStatus;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

public record OrderItemDto(
        Long id,
        Integer quantity,
        String productName,
        @Enumerated(EnumType.STRING)
        FulfillmentStatus fulfillmentStatus
) {
}
