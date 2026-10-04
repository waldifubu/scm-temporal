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

    /**
     * The supplier accepts the request: OPEN to APPROVED. Any other status is a 409.
     *
     * @param userId the acting user - only the supplier the request was placed with may answer it,
     *               anybody else is a 403 (ADMIN excepted)
     */
    RequestComponentResponse approveRequest(Long requestId, Long userId);

    /** The supplier has sent the goods: APPROVED to IN_TRANSIT. Any other status is a 409. */
    RequestComponentResponse requestInTransit(Long requestId, Long userId);

    /**
     * The supplier reports the goods handed over: IN_TRANSIT to DELIVERED. Any other status is a 409.
     * <p>
     * Their last step - what follows is our goods receipt, see {@link #receiveRequest}. A claim and
     * not a check: nothing is booked here.
     */
    RequestComponentResponse requestDelivered(Long requestId, Long userId);

    /**
     * The warehouse books the delivery in: DELIVERED to IN_STOCK, and the requested quantity is
     * added to {@code Stock.onHand} for this component's SKU in the receiving storehouse. The end of
     * the request's life, and the only place component stock grows other than by hand.
     * <p>
     * The storehouse comes from the caller and not from the request, which has no such field:
     * booked in is where the goods actually arrived, not where somebody wanted them two weeks ago.
     * Unknown storehouse is a 404, any status but DELIVERED a 409 - the supplier reports the handover
     * first. Should a supplier never report it, an ADMIN can do it for them: the ADMIN exemption on
     * the supplier steps is the escape hatch, so the gate is strict without being a dead end. All or nothing per row - a
     * request of 12 arriving as 8 + 4 cannot be expressed by one status.
     *
     * @param userId the acting user, for symmetry with the other steps
     */
    RequestComponentResponse receiveRequest(Long requestId, Long storehouseId, Long userId);
}
