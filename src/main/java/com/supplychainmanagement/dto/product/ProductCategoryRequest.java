package com.supplychainmanagement.dto.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** What a client may send to create or change a category; the id comes from the path, never from the body. */
public record ProductCategoryRequest(
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name is at most 255 characters") String name,
        @Size(max = 1000, message = "description is at most 1000 characters") @Nullable String description
) {
}
