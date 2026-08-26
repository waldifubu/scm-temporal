package com.supplychainmanagement.dto.product;

import java.util.UUID;

/**
 * Component inside a {@link ProductDto} - without the back-reference to the product,
 * which would otherwise create a cycle during serialisation.
 */
public record ProductComponentDto(
        Long id,
        String manufacturer,
        String name,
        String articleNo,
        String description,
        Double weight,
        UUID sku
) {
}
