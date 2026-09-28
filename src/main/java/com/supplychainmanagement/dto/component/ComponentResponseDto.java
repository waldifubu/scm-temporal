package com.supplychainmanagement.dto.component;

/**
 * A component as the API answers it. {@code qty} is the bill-of-materials quantity - how many of it
 * go into one unit of the product.
 */
public record ComponentResponseDto(
        Long id,
        String manufacturer,
        String name,
        String sku,
        String articleNo,
        Integer qty,
        ProductRefDto product
) {
}
