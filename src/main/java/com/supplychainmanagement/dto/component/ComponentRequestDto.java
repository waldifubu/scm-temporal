package com.supplychainmanagement.dto.component;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a client may send to create or change a component - and nothing else.
 * <p>
 * Deliberately not the {@code Component} entity. Bound from a body, that one accepted every field it
 * has, <strong>{@code id} included</strong>, and {@code create} handed it straight to
 * {@code save()}: with an id present that is a merge, so a {@code POST} overwrote whichever
 * component already carried that id. Nothing in the endpoint said so and nothing failed. Same reason
 * {@code UserController} stopped taking the {@code User} entity, and the same fix.
 * <p>
 * The product is named by its <strong>{@code articleNo}</strong> rather than as a nested object. It
 * used to arrive as {@code {"product": {"id": 3}}} - an entity inside an entity, of which exactly
 * one field was ever read, while everything else a client put in there was silently bound and
 * ignored. {@code articleNo} is unique and NOT NULL on {@code Product} and is what the rest of the
 * API already names a product by ({@code GET /products/&#123;articleNo&#125;}).
 *
 * @param productArticleNo which product this is a bill-of-materials line of - required, because
 *                         {@code Component.product_id} is NOT NULL: a component belongs to exactly
 *                         one product and is not a shared catalogue part
 * @param qty              how many of it go into one unit of that product. Optional; the entity
 *                         lifts a missing or non-positive value to 1, because a line that is part of
 *                         the recipe is needed at least once
 * @param sku              optional - left out, the entity keeps whatever it has. It is unique, so
 *                         sending one that exists is a 409
 * @param weight           optional; the entity invents one when it is missing or zero
 */
public record ComponentRequestDto(
        @NotNull(message = "productArticleNo is required") Long productArticleNo,
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name is at most 255 characters") String name,
        @Size(max = 255, message = "manufacturer is at most 255 characters") String manufacturer,
        @Size(max = 255, message = "articleNo is at most 255 characters") String articleNo,
        @Size(max = 255, message = "description is at most 255 characters") String description,
        @PositiveOrZero(message = "weight cannot be negative") BigDecimal weight,
        @Size(max = 255, message = "externalId is at most 255 characters") String externalId,
        UUID sku,
        @Positive(message = "qty has to be at least 1") Integer qty
) {
}
