package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.reservation.ReservationResult;
import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.entity.Reservation;

import java.util.List;

public interface InventoryService {
    ReservationResult reserveWithRetry(String orderId, List<ReserveItem> items);

    /**
     * @return the reservations that were actually released, so a caller can report what it handed
     *         back instead of assuming its input was processed in full
     */
    List<Reservation> releaseWithRetry(String orderId, List<ReserveItem> items);

    /**
     * @return the reservations that were actually consumed. A line without stock or without an
     *         active reservation is skipped rather than thrown on, so an empty or short list is the
     *         only sign that nothing - or not everything - was taken out of stock
     */
    List<Reservation> consumeWithRetry(String orderId, List<ReserveItem> items);
}
