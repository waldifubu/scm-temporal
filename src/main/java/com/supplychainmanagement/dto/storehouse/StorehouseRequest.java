package com.supplychainmanagement.dto.storehouse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * What a client may change on a storehouse ({@code PUT /storehouses/{id}}). No id - it is in the path - and
 * never the stock it holds. Blank address, city and country are stored as {@code null}.
 */
public record StorehouseRequest(
        @NotBlank @Size(max = 255) String name,
        @Nullable @Size(max = 255) String address,
        @Nullable @Size(max = 255) String city,
        @Nullable @Size(max = 255) String country
) {
}
