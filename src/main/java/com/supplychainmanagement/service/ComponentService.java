package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.component.ComponentRequestDto;
import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentsRequest;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.model.enums.RequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    /**
     * Creates a component from what a client may send - see {@link ComponentRequestDto}. The product
     * is resolved by {@code articleNo} (404 when there is none), and a duplicate SKU or externalId is
     * a 409.
     * <p>
     * It used to take the {@code Component} entity, which meant a client could send an {@code id} and
     * have {@code save()} merge over an existing row.
     */
    Component create(ComponentRequestDto request);

    /** Changes a component. The id comes from the path, never from the body. */
    Component update(Long id, ComponentRequestDto request);

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
     * @param userId     who is ordering - kept on every row as {@code assignedBy}. Unresolvable
     *                   leaves the column null rather than failing the request
     */
    List<RequestComponentResponse> requestComponents(Long supplierId, RequestComponentsRequest request,
                                                     Long userId);

    /**
     * One page of component requests, all of them or those in one status.
     * <p>
     * The warehouse's work list, and the reason it exists: the goods receipt takes a request id, and
     * until now the only way to read requests was {@code GET /components/my-requests}, which is the
     * supplier's own list ({@code findBySupplierId}). The role that has to call the receipt had no way
     * to find out which request is {@code DELIVERED}, and an ADMIN asking the supplier's list got
     * their own (empty) one. Same shape as the carrier's and the planner's own views.
     *
     * @param status the status to narrow to, or null for every request
     */
    Page<RequestComponentResponse> findRequests(RequestStatus status, Pageable pageable);

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
     * The supplier declines a request they have not taken on: OPEN to REJECTED. Any other status is a
     * 409 - once approved it is a cancellation, see {@link #cancelRequest}.
     * <p>
     * An end state. Nothing was promised and nothing booked, so there is nothing to undo.
     *
     * @param userId the acting user - only the supplier the request was placed with may answer it,
     *               anybody else is a 403 (ADMIN excepted)
     */
    RequestComponentResponse rejectRequest(Long requestId, Long userId);

    /**
     * The supplier calls off a request they had taken on: APPROVED or IN_TRANSIT to CANCELLED. Any
     * other status is a 409.
     * <p>
     * Deliberately not from DELIVERED: the goods are at our dock then, and calling the request off
     * would be a return, which this process does not model. Not from OPEN either - that is
     * {@link #rejectRequest}, and the two say different things. Nothing is booked back, because
     * nothing was ever booked: stock only grows at the goods receipt.
     *
     * @param userId the acting user - only the supplier the request was placed with may answer it,
     *               anybody else is a 403 (ADMIN excepted)
     */
    RequestComponentResponse cancelRequest(Long requestId, Long userId);

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
