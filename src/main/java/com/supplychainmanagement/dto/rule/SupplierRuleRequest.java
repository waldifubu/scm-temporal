package com.supplychainmanagement.dto.rule;

import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

/**
 * What a client sends to create or change a supplier rule. A condition left out ({@code null}) is no condition: the
 * rule then applies to every component, or every product.
 *
 * @param supplierId  the supplier the rule hands requests to - an active or inactive one, but a {@code Supplier}
 * @param componentId only for this component
 * @param productId   only for components of this product
 * @param priority    optional, 0 when left out; breaks a tie between equally specific rules; the higher one wins
 */
public record SupplierRuleRequest(
        @NotNull Long supplierId,
        @Nullable Long componentId,
        @Nullable Long productId,
        @Nullable Integer priority
) {
}
