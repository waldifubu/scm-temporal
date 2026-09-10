package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.fullfillment.AvailableOrderItemDto;
import com.supplychainmanagement.dto.fullfillment.ProductionResultDto;
import com.supplychainmanagement.dto.reservation.ReservationSummary;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.Reservation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

public interface FulfillmentService {

    ReservationSummary reserveItems(Order order, String username);

    /**
     * Releases everything the order still holds.
     *
     * @param username the acting user, resolved for the audit trail the same way reserveItems does
     * @return the reservations that were actually released
     */
    List<Reservation> releaseItems(Order order, String username);

    /**
     * Releases exactly the reservations handed in, and nothing else. The expiry sweep passes only
     * what has run out: lines reserved by a later call have a later expiry, so an order can hold a
     * fresh reservation next to an expired one, and releasing all of them would give back stock that
     * is still legitimately held.
     *
     * @return the reservations that were actually released
     */
    List<Reservation> releaseItems(Order order, List<Reservation> reservations, String username);

    /**
     * Every reservation whose {@code expiresAt} has passed, grouped by the order holding it - all a
     * sweep needs to call {@link #releaseItems(Order, List, String)} per order.
     */
    Map<Order, List<Reservation>> findExpiredReservationsByOrder();
}
