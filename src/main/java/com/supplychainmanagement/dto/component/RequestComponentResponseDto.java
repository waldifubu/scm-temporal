package com.supplychainmanagement.dto.component;

import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.model.enums.RequestStatus;

import java.time.LocalDateTime;

/**
 * A component as the API answers it. {@code qty} is the bill-of-materials quantity - how many of it
 * go into one unit of the product.
 */
public record RequestComponentResponseDto(
        Long id,
        String comment,
        LocalDateTime requestDate,
        RequestStatus requestStatus,
        LocalDateTime updated,
        Integer qty,
        Component component
) {
}
