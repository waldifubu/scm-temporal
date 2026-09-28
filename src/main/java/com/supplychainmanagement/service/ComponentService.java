package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentsRequest;
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

    /**
     * Orders components from one supplier: every line of the request becomes a row of its own in
     * {@code request_components}, in {@code RequestStatus.OPEN}.
     * <p>
     * The lines name their component by <strong>SKU</strong>, not by the numeric id. Repeating a SKU
     * is allowed and is not folded into one line: two requests for the same part, each with its own
     * quantity and its own comment, are two requests - unlike an order, where
     * {@code mergeDuplicateProducts} adds a repeated article up.
     * <p>
     * All or nothing: an unknown SKU refuses the whole request (404, naming every one of them), so a
     * caller never has to work out which half of its list was placed.
     *
     * @param supplierId the user to order from - 404 when there is no such user, 400 when they are
     *                   not a {@code Supplier}
     */
    List<RequestComponentResponse> requestComponents(Long supplierId, RequestComponentsRequest request);
}
