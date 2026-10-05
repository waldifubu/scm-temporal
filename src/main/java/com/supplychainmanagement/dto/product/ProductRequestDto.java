package com.supplychainmanagement.dto.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What a client may send to create or change a product - and nothing else.
 * <p>
 * Deliberately not the {@code Product} entity. Bound from a body that one took every field it has,
 * and two of them were dangerous. {@code id}: {@code create} passed the bound object to
 * {@code save()}, which with an id present is a merge - a {@code POST} overwrote an existing
 * product. And {@code components}: the association carries {@code cascade = ALL}, so a line sent
 * with the id of a component belonging to <em>another</em> product was reassigned to this one by the
 * cascade, quietly rewriting that product's bill of materials. {@code createdAt},
 * {@code updatedAt} and the {@code products} back-reference inside a category were bindable too.
 * <p>
 * Categories arrive as <strong>ids</strong>. They used to arrive as whole {@code ProductCategory}
 * objects, which have a {@code Set<Product>} of their own - a product inside a category inside a
 * product, all of it bound and most of it ignored.
 *
 * @param components the bill of materials. Optional: left out, the product keeps the lines it has.
 *                   A line without an {@code id} is new; a line with one must be a line of
 *                   <strong>this</strong> product (400 otherwise), and on create an id is refused
 *                   outright - a product that does not exist yet has no existing lines
 */
public record ProductRequestDto(
        @NotNull(message = "articleNo is required") Long articleNo,
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name is at most 255 characters") String name,
        @Size(max = 1000, message = "description is at most 1000 characters") String description,
        @PositiveOrZero(message = "unitPrice cannot be negative") BigDecimal unitPrice,
        @PositiveOrZero(message = "weight cannot be negative") BigDecimal weight,
        UUID sku,
        Boolean active,
        Set<Long> categoryIds,
        @Valid List<ComponentLine> components
) {

    /**
     * One bill-of-materials line of the product.
     *
     * @param id  the line to change, or null for a new one. It is checked against the product's own
     *            lines rather than taken at face value - that check is the whole point of this DTO
     * @param qty how many go into one unit of the product. Note that an <em>existing</em> line's qty
     *            is not currently changed by a product update; see issues.txt
     */
    public record ComponentLine(
            Long id,
            @NotBlank(message = "component name is required")
            @Size(max = 255, message = "component name is at most 255 characters") String name,
            @Size(max = 255, message = "manufacturer is at most 255 characters") String manufacturer,
            @Size(max = 255, message = "articleNo is at most 255 characters") String articleNo,
            @Size(max = 255, message = "description is at most 255 characters") String description,
            @PositiveOrZero(message = "weight cannot be negative") BigDecimal weight,
            @Size(max = 255, message = "externalId is at most 255 characters") String externalId,
            UUID sku,
            @Positive(message = "qty has to be at least 1") Integer qty
    ) {
    }
}
