package com.supplychainmanagement.dto.product;

import org.jspecify.annotations.Nullable;
import java.util.UUID;

/**
 * Component inside a {@link ProductDto} - without the back-reference to the product,
 * which would otherwise create a cycle during serialisation.
 * <p>
 * {@code qty} is how many of the component go into one unit of the product, so the recipe is
 * readable from the product itself.
 */
public record ProductComponentDto(
        Long id,
        @Nullable String manufacturer,
        @Nullable String name,
        @Nullable String articleNo,
        @Nullable String description,
        @Nullable Double weight,
        UUID sku,
        Integer qty
) {
}
