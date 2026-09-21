package com.supplychainmanagement.dto.fullfillment;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * The answer of POST /produce: the fields of {@link com.supplychainmanagement.dto.common.PageResponse},
 * so a client reading pages reads this one the same way, plus {@code produced} - how many products
 * this call actually built.
 * <p>
 * {@code total} counts the products paged over, not the produced ones, which is why the count needs
 * a field of its own.
 */
public record ProductionPageResponse(
        List<ProductionResultDto> content,
        long total,
        int page,
        int size,
        long produced) {

    public static ProductionPageResponse of(Page<ProductionResultDto> page) {
        // Counted rather than taken from content.size(): the page only carries successful runs
        // today, but the count must not depend on that.
        long produced = page.getContent().stream().filter(ProductionResultDto::produced).count();
        return new ProductionPageResponse(page.getContent(), page.getTotalElements(),
                page.getNumber(), page.getSize(), produced);
    }
}
