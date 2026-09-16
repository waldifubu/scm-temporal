package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.reservation.ReservationSummary;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.Reservation;

import java.util.List;

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
     * The orders holding at least one reservation whose {@code expiresAt} has passed, each once and
     * with its lines loaded - all a sweep needs to call {@link #releaseItems(Order, String)} per
     * order.
     */
    List<Order> findOrdersWithExpiredReservations();

    /**
     * The reservations that are still active for the given order, i.e. {@code expiresAt} is in the
     * future.
     */
    List<Reservation> findActiveReservations(Order order);

    List<Reservation> findConsumedReservations(Order order);

    Reservation deleteReservation(Reservation reservation, String systemUser);
}
