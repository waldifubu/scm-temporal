package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.reservation.ReservationResult;
import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.entity.Reservation;

import java.util.List;

public interface InventoryService {
    ReservationResult reserveWithRetry(Long orderId, List<ReserveItem> items);

    /**
     * @return the reservations that were actually released, so a caller can report what it handed
     *         back instead of assuming its input was processed in full
     */
    List<Reservation> releaseWithRetry(Long orderId, List<ReserveItem> items);

    /**
     * @return the reservations that were actually consumed. A line without stock or without an
     *         active reservation is skipped rather than thrown on, so an empty or short list is the
     *         only sign that nothing - or not everything - was taken out of stock
     */
    List<Reservation> consumeWithRetry(Long orderId, List<ReserveItem> items);

    /**
     * Takes a pick back: the consumed quantity goes onto the shelf again and the {@code CONSUMED}
     * reservation of each line is deleted.
     *
     * @return the reservations that were deleted. A line without a stock row or without a consumed
     *         reservation is skipped rather than thrown on, as in {@link #consumeWithRetry} - an empty or
     *         short list is the only sign that nothing, or not everything, was handed back
     */
    List<Reservation> restoreWithRetry(Long orderId, List<ReserveItem> items);
}
