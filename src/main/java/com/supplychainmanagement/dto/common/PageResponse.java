package com.supplychainmanagement.dto.common;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Slim page DTO consumed by the frontend dataProvider.
 * {@code total} carries the overall count for react-admin pagination.
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
