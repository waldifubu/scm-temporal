package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The transaction boundary for picking a single order line, on its own bean for a reason: picking a
 * whole order loops over its lines, and a private method called from inside
 * {@link OrderHandlingServiceImpl} would go around the proxy and be transactional in name only -
 * the same trap {@code InventoryServiceImpl} / {@code InventoryReservationTransactionService}
 * already work around.
 * <p>
 * The boundary sits at the <em>line</em>, not at the order, and that is deliberate. The stock side
 * commits on its own regardless ({@code consumeWithRetry} delegates to a REQUIRES_NEW transaction),
 * so wrapping the whole loop would let a failure on line three roll back the line statuses of lines
 * one and two while their stock stays consumed - a reservation reading ACTIVE over stock that is
 * already gone, and a second attempt consuming it twice. Per line, a failure leaves the lines
 * before it correctly and completely picked.
 */
@Service
@RequiredArgsConstructor
public class OrderHandlingTransactionService {

    private final OrderItemRepository orderItemRepository;
    private final ReservationRepository reservationRepository;
    private final InventoryService inventoryService;

    /**
     * Picks one reservation: the line item goes to PICKING, the reserved stock is consumed, the
     * reservation becomes CONSUMED, and only then does the line item reach PICKED.
     * <p>
     * The order is handed in rather than looked up from {@code reservation.getOrderId()}: picking a
     * whole order would otherwise repeat the same lookup for every single line.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PickingOrderDto pick(Order order, Reservation reservation) {
        OrderItem orderItem = orderItemValidation(order, reservation);

        orderItem.setFulfillmentStatus(FulfillmentStatus.PICKING);
        orderItemRepository.save(orderItem);

        try {
            inventoryService.consumeWithRetry(String.valueOf(order.getId()), List.of(new ReserveItem(orderItem.getId(), reservation.getSku(), reservation.getQuantity(), reservation.getStorehouse().getId())));
        } catch (Exception e) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Failed to consume items for reservation " + reservation.getId() + ": " + e.getMessage());
        }

        reservation.setStatus(ReservationStatus.CONSUMED);
        var newReservation = reservationRepository.save(reservation);
        if (newReservation.getStatus() != ReservationStatus.CONSUMED) {
            throw new APIException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update reservation status for reservation " + reservation.getId());
        }

        // Only after the reservation is really consumed, so a line never reads as PICKED while its
        // stock is still reserved.
        orderItem.setFulfillmentStatus(FulfillmentStatus.PICKED);
        orderItemRepository.save(orderItem);

        // Mapped in here, while the transaction is open, so the LAZY product and storehouse are
        // resolved before the result leaves the boundary.
        return PickingOrderDto.of(order, newReservation);
    }

    private static @NonNull OrderItem orderItemValidation(Order order, Reservation reservation) {
        OrderItem orderItem = reservation.getOrderItem();
        // Check orderItem is in Order
        if (!orderItem.getOrder().getId().equals(order.getId())) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Reservation " + reservation.getId() + " does not belong to order " + order.getId());
        }
        if (orderItem.getFulfillmentStatus() == FulfillmentStatus.PICKED) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item is already in PICKED status, cannot move to PICKED");
        }
        if (orderItem.getFulfillmentStatus() != FulfillmentStatus.RESERVED) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item is not in RESERVED status, cannot move to PICKED");
        }
        return orderItem;
    }
}
