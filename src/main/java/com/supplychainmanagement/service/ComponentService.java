package com.supplychainmanagement.service;

import com.supplychainmanagement.entity.Component;

import java.util.List;
import java.util.UUID;

/**
 * Deliberately synchronous - see {@link OrderService}: with Mono/Flux return types the
 * {@code @Transactional} proxy committed before the actual database work had even started.
 */
public interface ComponentService {

    List<Component> findAll();

    Component findById(Long id);

    Component findBySku(UUID sku);

    Component findByArticleNo(String articleNo);

    Component create(Component component);

    Component update(Long id, Component component);

    void deleteById(Long id);
}
