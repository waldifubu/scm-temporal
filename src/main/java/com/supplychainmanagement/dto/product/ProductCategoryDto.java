package com.supplychainmanagement.dto.product;

import org.jspecify.annotations.Nullable;
public record ProductCategoryDto(
        Long id,
        String name,
        @Nullable String description
) {
}
