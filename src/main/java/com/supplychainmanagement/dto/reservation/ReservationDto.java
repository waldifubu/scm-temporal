package com.supplychainmanagement.dto.reservation;

import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.model.enums.ReservationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A reservation as the inventory endpoints answer with it.
 * <p>
 * The entity itself cannot be handed to the response writer here. The inventory layer runs
 * REQUIRES_NEW, so what it returns comes out of a session that is already closed, and
 * {@code orderItem} and {@code storehouse} are LAZY proxies of that session - Jackson reaches into
 * them and fails with "Could not initialize proxy - no session". Open-in-view does not help: its
 * session is a different one.
 * <p>
 * Related entities therefore appear as ids only. Hibernate answers {@code getId()} on a proxy from
 * the identifier it already holds, without initializing it, so {@link #of} is safe on a detached
 * reservation - which is the point.
 */
public record ReservationDto(
        Long id,
        String orderId,
        Long orderItemId,
        UUID sku,
        int quantity,
        Long storehouseId,
        ReservationStatus status,
        LocalDateTime expiresAt
) {

    public static ReservationDto of(Reservation reservation) {
        OrderItem orderItem = reservation.getOrderItem();
        Storehouse storehouse = reservation.getStorehouse();

        return new ReservationDto(
                reservation.getId(),
                reservation.getOrderId(),
                // Null on legacy rows from before order_item_id existed.
                orderItem != null ? orderItem.getId() : null,
                reservation.getSku(),
                reservation.getQuantity(),
                storehouse != null ? storehouse.getId() : null,
                reservation.getStatus(),
                reservation.getExpiresAt());
    }
}
