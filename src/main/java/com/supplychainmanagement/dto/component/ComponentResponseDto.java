package com.supplychainmanagement.dto.component;

import org.jspecify.annotations.Nullable;
/**
 * A component as the API answers it. {@code qty} is the bill-of-materials quantity - how many of it
 * go into one unit of the product.
 */
public record ComponentResponseDto(
        Long id,
        @Nullable String manufacturer,
        @Nullable String name,
        String sku,
        @Nullable String articleNo,
        Integer qty,
        ProductRefDto product
) {
}
