package com.supplychainmanagement.dto.order;

import com.supplychainmanagement.model.enums.FulfillmentStatus;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.UUID;

/**
 * One line of an order. {@code articleNo} is what the product is called everywhere in the API (the products are
 * addressed by it, {@code GET /products/{articleNo}}) and {@code sku} the stock-keeping unit behind it, for a
 * client to link the line to its product and to show the SKU on hover. Both are {@code null} for a line
 * without a product. The product's database id is not here: the public view of a product hides it.
 */
public record OrderItemDto(
        Long id,
        Integer quantity,
        String productName,
        Long articleNo,
        UUID sku,
        @Enumerated(EnumType.STRING)
        FulfillmentStatus fulfillmentStatus
) {
}
