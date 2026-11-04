package com.supplychainmanagement.dto.component;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One component with everything {@code PATCH /components/{sku}} can change: what the details page shows
 * and fills its edit form from. A separate record from {@link ComponentResponseDto} on purpose - that
 * one is also nested in the supplier's own request list, and a supplier has no business with the
 * external id or the weight of a part.
 */
public record ComponentDetailDto(
        Long id,
        String manufacturer,
        String name,
        String description,
        String sku,
        String articleNo,
        String externalId,
        BigDecimal weight,
        Integer qty,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updatedAt,
        ProductRefDto product
) {
}
