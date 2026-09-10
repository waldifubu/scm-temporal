package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.entity.*;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.service.InventoryService;
import com.supplychainmanagement.service.OrderHandlingService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderHandlingServiceImpl implements OrderHandlingService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ReservationRepository reservationRepository;
    private final OrderHandlingTransactionService transactionService;

    /**
     * A projection, not entities: the page is one query and carries nothing lazy into the response.
     * See {@link PickingOrderDto} for why the endpoint stopped handing out the Reservation itself.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PickingOrderDto> pickingOrders(ReservationStatus reservationStatus, Pageable pageable) {
        return reservationRepository.findPickingOrders(reservationStatus, pageable);
    }

    /**
     * Picks the single reservation with that id. Which reservation is meant is the only thing this
     * method decides - the picking itself, and its transaction, is
     * {@link OrderHandlingTransactionService#pick}.
     */
    @Override
    public PickingOrderDto pickingReservationById(Long reservationId) {
        var reservation = reservationRepository.findByIdAndStatus(reservationId, ReservationStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", "id maybe already converted", reservationId));
        Order order = findOrder(reservation.getOrderId());

        return transactionService.pick(order, reservation);
    }


    /**
     * Picks every active reservation of the order. Not idempotent per call the way reserving is:
     * a reservation already consumed is no longer ACTIVE and simply does not turn up again.
     * <p>
     * A failure on one line aborts the whole loop - the lines picked before it stay picked, since
     * each of them is committed in its own transaction. See
     * {@link OrderHandlingTransactionService} for why the boundary sits at the line and not here.
     */
    @Override
    public List<PickingOrderDto> pickingReservationByOrderNo(String orderNo) {
        Order order = findOrder(orderNo);

        // Queried with the order's id, not with the orderNo that came in: Reservation.orderId holds
        // the numeric Order.id as a String, so passing the orderNo through would find nothing for
        // every order whose two numbers differ.
        return reservationRepository.findByOrderIdAndStatus(String.valueOf(order.getId()), ReservationStatus.ACTIVE).stream()
                .map(reservation -> transactionService.pick(order, reservation))
                .toList();
    }


    @Override
    @Transactional
    public PickingOrderDto readyForDispatch(Long reservationId) {
        var reservation = reservationRepository.findByIdAndStatus(reservationId, ReservationStatus.CONSUMED)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", "id not found", reservationId));

        var orderItem = reservation.getOrderItem();
        if (orderItem.getFulfillmentStatus() == FulfillmentStatus.READY_FOR_DISPATCH) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item is already in READY_FOR_DISPATCH status, cannot move to READY_FOR_DISPATCH");
        }
        if (orderItem.getFulfillmentStatus() != FulfillmentStatus.PACKED) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item is not in PACKED status, cannot move to READY_FOR_DISPATCH");
        }
        orderItem.setFulfillmentStatus(FulfillmentStatus.READY_FOR_DISPATCH);
        orderItemRepository.save(orderItem);

        return PickingOrderDto.of(findOrder(reservation.getOrderId()), reservation);
    }

    /**
     * {@code Reservation.orderId} is a String while {@code Order.id} is numeric - the conversion and
     * the lookup live in one place instead of at every call site. Falls back to the orderNo so both
     * identifiers reach the same order.
     */
    private Order findOrder(String orderId) {
        Long id = Long.valueOf(orderId);

        return orderRepository.findById(id)
                .orElseGet(() -> orderRepository.findByOrderNo(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Order", "id", id)));
    }
}
