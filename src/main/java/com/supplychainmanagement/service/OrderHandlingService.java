package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.picking.PickingOrderDto;
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

    Page<PickingOrderDto> pickingOrders(ReservationStatus reservationStatus, Pageable pageable);

    PickingOrderDto pickingReservationById(Long reservationId);

    List<PickingOrderDto> pickingReservationByOrderNo(String orderNo);

    PickingOrderDto readyDispatch(Long reservationId);
}
