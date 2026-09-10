package com.supplychainmanagement.dto.picking;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.model.enums.FulfillmentStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of the picking list: everything the warehouse needs to walk to a shelf and take the goods,
 * flattened out of Reservation, OrderItem, Product, Order and Storehouse.
 * <p>
 * The endpoint deliberately no longer answers with the Reservation entity. Serializing it pulled
 * every LAZY reference during rendering - a query per row and per level - and the chain
 * OrderItem -> Order -> orderItems -> OrderItem closes into a cycle Jackson cannot resolve. A
 * projection also keeps the response shape independent of the entity mapping, which is what let the
 * order item change leak into the API in the first place.
 */
public record PickingOrderDto(
        Long reservationId,
        Long orderNo,
        Long orderItemId,
        Long articleNo,
        String productName,
        UUID sku,
        int quantity,
        Long storehouseId,
        String storehouseName,
        FulfillmentStatus fulfillmentStatus,
        LocalDateTime expiresAt
) {

    /**
     * Flattens a reservation and its order line into one row.
     * <p>
     * Call this while a transaction is still open: {@code orderItem.product} and
     * {@code reservation.storehouse} are LAZY, and resolving them from the response writer instead
     * is exactly what this DTO exists to prevent.
     */
    public static PickingOrderDto of(Order order, Reservation reservation) {
        OrderItem orderItem = reservation.getOrderItem();
        Product product = orderItem.getProduct();
        Storehouse storehouse = reservation.getStorehouse();

        return new PickingOrderDto(
                reservation.getId(),
                order.getOrderNo(),
                orderItem.getId(),
                product.getArticleNo(),
                product.getName(),
                reservation.getSku(),
                reservation.getQuantity(),
                storehouse.getId(),
                storehouse.getName(),
                orderItem.getFulfillmentStatus(),
                reservation.getExpiresAt());
    }
}
