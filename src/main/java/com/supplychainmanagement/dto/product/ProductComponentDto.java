package com.supplychainmanagement.dto.product;

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
        String manufacturer,
        String name,
        String articleNo,
        String description,
        Double weight,
        UUID sku,
        Integer qty
) {
}
