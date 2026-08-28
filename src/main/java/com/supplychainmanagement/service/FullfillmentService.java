package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.fullfillment.AvailableOrderItemDto;
import com.supplychainmanagement.dto.fullfillment.ProductionResultDto;
import com.supplychainmanagement.dto.reservation.ReservationSummary;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.model.enums.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface FullfillmentService {
    Page<ProductionResultDto> produce(Pageable pageable);

    List<AvailableOrderItemDto> checkItems(Order order);

    ReservationSummary reserveItems(Order order, String username);

    void releaseItems(Order order);

    Page<Reservation> pickingOrders(ReservationStatus reservationStatus, Pageable pageable);

    Reservation pickingReservationById(Long reservationId);

    List<Reservation> pickingReservationByOrderNo(String orderNo);
}
