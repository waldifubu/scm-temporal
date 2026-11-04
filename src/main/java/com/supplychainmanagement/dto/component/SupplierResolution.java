package com.supplychainmanagement.dto.component;

import java.util.UUID;

/**
 * Whom a request for one component would go to, by the supplier rules: the answer of
 * {@code GET /components/suppliers/resolve}. {@code supplierId}, {@code supplierName} and
 * {@code ruleId} are {@code null} when no active supplier rule applies - the same component
 * {@code POST /components/request} would refuse.
 */
public record SupplierResolution(UUID componentId, Long supplierId, String supplierName, Long ruleId) {
}
