package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.reservation.ReservationResult;
import com.supplychainmanagement.dto.reservation.ReserveItem;

import java.util.List;

public interface InventoryService {
    ReservationResult reserveWithRetry(String orderId, List<ReserveItem> items);

    void releaseWithRetry(String orderId, List<ReserveItem> items);

    void consumeWithRetry(String orderId, List<ReserveItem> items);
}
