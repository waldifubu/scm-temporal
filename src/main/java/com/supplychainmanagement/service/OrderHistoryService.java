package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.order.OrderHistoryResponse;

import java.util.List;

public interface OrderHistoryService {

    /**
     * The status changes of one order, newest first - the latest step is the first row.
     * <p>
     * Whoever may read the order may read its history, by exactly the same rule and with the same
     * answers: 404 for an unknown order, 403 for a customer asking about somebody else's. A history
     * says more than the order does, so it must never be reachable where the order is not.
     * <p>
     * Not paged: an order has as many rows as it has had status changes, a handful.
     */
    List<OrderHistoryResponse> findHistory(Long orderNo, org.springframework.security.core.userdetails.User authUser);
}
