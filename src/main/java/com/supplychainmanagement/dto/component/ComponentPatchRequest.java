package com.supplychainmanagement.dto.component;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * The part of a component a client may change with {@code PATCH /components/{sku}}: its name, its
 * manufacturer, its description, its external id, its weight and the quantity that goes into one unit of the product.
 * <p>
 * Every field is optional and a field that is left out stays as it is - that is what separates a
 * patch from {@link ComponentRequestDto}, which replaces the lot. At least one has to be sent. The
 * SKU comes from the path and, like the product and the identifiers, cannot be changed here.
 *
 * @param name         not blank when sent
 * @param manufacturer an empty string clears it
 * @param description an empty string clears it
 * @param externalId  unique; one that another component carries is a 409, an empty string clears it
 * @param weight      not negative
 * @param qty          at least 1 - a recipe line is needed at least once
 */
public record ComponentPatchRequest(
        @Pattern(regexp = ".*\\S.*", message = "name must not be blank")
        @Size(max = 255, message = "name is at most 255 characters") String name,
        @Size(max = 255, message = "manufacturer is at most 255 characters") String manufacturer,
        @Size(max = 255, message = "description is at most 255 characters") String description,
        @Size(max = 255, message = "externalId is at most 255 characters") String externalId,
        @PositiveOrZero(message = "weight cannot be negative") BigDecimal weight,
        @Positive(message = "qty has to be at least 1") Integer qty
) {
}
