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

    void consumeWithRetry(String orderId, List<ReserveItem> items);
}
