package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.reservation.ReserveItem;

import java.util.List;

public interface InventoryService {
    void reserveWithRetry(String orderId, List<ReserveItem> items);

    void releaseWithRetry(String orderId, List<ReserveItem> items);

    void consume(String orderId, List<ReserveItem> items);

}
