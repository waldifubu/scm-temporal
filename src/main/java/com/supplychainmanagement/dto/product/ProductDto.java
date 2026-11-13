package com.supplychainmanagement.dto.product;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Outward-facing view of a product.
 * <p>
 * Deliberately not the {@code Product} entity: that one used to be mutated in the controller
 * ({@code setId(null)}, {@code setComponents(null)}) to keep fields out of the JSON - on a managed
 * entity the next flush would have turned that into deleted rows. What a non-privileged caller
 * must not see is simply left unpopulated here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductDto(
        @Nullable Long id,
        Long articleNo,
        String name,
        @Nullable String description,
        BigDecimal unitPrice,
        Double weight,
        UUID sku,
        boolean active,
        LocalDateTime createdAt,
        @Nullable LocalDateTime updatedAt,
        @Nullable Set<ProductCategoryDto> categories,
        @Nullable List<ProductComponentDto> components
) {
}
