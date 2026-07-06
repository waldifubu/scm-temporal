package com.example.supplychainmanagement.dto.component;

public record ComponentResponseDto(
        Long id,
        String manufacturer,
        String name,
        String sku,
        String articleNo,
        ProductRefDto product
) {
}
