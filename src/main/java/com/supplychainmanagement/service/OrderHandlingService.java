package com.supplychainmanagement.service;

import org.jspecify.annotations.Nullable;
import com.supplychainmanagement.dto.order.OrderItemListDto;
import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * What the warehouse does with an order that is already reserved - picking today, the later
 * fulfillment steps as they arrive. Deliberately separate from {@link FulfillmentService}, which is
 * about getting an order reserved in the first place and ends where this one starts.
 */
public interface OrderHandlingService {

    /** The rows of one stage, of every order or, with an {@code orderNo}, of that one. */
    Page<PickingOrderDto> pickingOrders(ReservationStatus reservationStatus, @Nullable Long orderNo, Pageable pageable);

    PickingOrderDto pickingReservationById(Long reservationId);

    List<PickingOrderDto> pickingReservationByOrderNo(String orderNo);

    /**
     * Takes back the pick behind a {@code CONSUMED} reservation: the line goes back to WAITING, the stock that
     * was taken off the shelf goes onto it again, and the reservation is deleted. 404 when there is no such
     * reservation (or it was never picked), 409 when the line has moved on from PICKED.
     */
    PickingOrderDto unpickReservationById(Long reservationId);

    /** The lines in this status ({@code null}: every status), of one order or ({@code null}) of all. */
    Page<OrderItemListDto> getOrderItems(FulfillmentStatus status, Long orderNo, Pageable pageable);

    /** The lines in any of these statuses, of one order or ({@code null}) of all. */
    Page<OrderItemListDto> getOrderItemsIn(java.util.Collection<FulfillmentStatus> statuses, Long orderNo,
                                           Pageable pageable);
}
