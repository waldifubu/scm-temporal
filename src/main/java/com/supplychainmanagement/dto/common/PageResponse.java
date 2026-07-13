package com.supplychainmanagement.dto.common;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Schlankes Seiten-DTO, das vom Frontend-dataProvider gelesen wird.
 * {@code total} liefert die Gesamtanzahl fuer die react-admin-Paginierung.
 */
public record PageResponse<T>(
        List<T> content,
        long total,
        int page,
        int size) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getTotalElements(),
                page.getNumber(), page.getSize());
    }
}
