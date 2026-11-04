package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.order.OrderShipmentResponse;

import java.util.List;

public interface OrderShipmentService {

    /**
     * The shipments an order travels in, oldest first - an empty list while none of its packages is in one.
     * <p>
     * Whoever may read the order may read this, by the same rule and with the same answers as
     * {@code GET /orders/{orderNo}}: 404 for an unknown order, 403 for a customer asking about somebody else's.
     * The carrier's name is only in the answer for staff.
     */
    List<OrderShipmentResponse> findShipments(Long orderNo, org.springframework.security.core.userdetails.User authUser);
}
