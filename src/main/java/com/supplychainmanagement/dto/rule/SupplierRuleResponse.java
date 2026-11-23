package com.supplychainmanagement.dto.rule;

import com.supplychainmanagement.dto.common.DisplayNames;
import com.supplychainmanagement.entity.SupplierRule;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

/**
 * One row of {@code supplier_rule}: which supplier a component request goes to, and under which conditions. A
 * condition that is {@code null} is no condition - the rule then matches every component or product.
 * Names only, never the entities: the supplier is a {@code User}.
 */
public record SupplierRuleResponse(
        Long id,
        Long supplierId,
        String supplierName,
        @Nullable Long componentId,
        @Nullable String componentName,
        @Nullable Long productId,
        @Nullable String productName,
        int priority,
        @Nullable LocalDateTime createdAt
) {

    public static SupplierRuleResponse from(SupplierRule rule) {
        var component = rule.getComponent();
        var product = rule.getProduct();
        return new SupplierRuleResponse(
                rule.getId(),
                rule.getSupplier().getId(),
                DisplayNames.of(rule.getSupplier()),
                component == null ? null : component.getId(),
                component == null ? null : component.getName(),
                product == null ? null : product.getId(),
                product == null ? null : product.getName(),
                rule.getPriority(),
                rule.getCreatedAt());
    }
}
