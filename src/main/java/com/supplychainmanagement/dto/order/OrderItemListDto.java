package com.supplychainmanagement.dto.order;

import com.supplychainmanagement.model.enums.FulfillmentStatus;

import java.time.LocalDateTime;

/**
 * One order line in a list across orders, filtered by fulfillment status.
 * <p>
 * Filled by a constructor projection in {@code OrderItemRepository.findAllByFulfillmentStatus},
 * like {@code PickingOrderDto}: one query per page, nothing lazy left for the response writer. Not
 * the OrderItem entity - OrderItem -> Order -> orderItems is a cycle Jackson cannot serialize. And
 * not PickingOrderDto either, which is built around a reservation that a WAITING line does not have.
 */
public record OrderItemListDto(
        Long orderItemId,
        // Null while the line holds no reservation - WAITING, or released again.
        Long reservationId,
        Long orderNo,
        Long articleNo,
        String productName,
        Integer quantity,
        FulfillmentStatus fulfillmentStatus,
        LocalDateTime updatedAt
) {
}
