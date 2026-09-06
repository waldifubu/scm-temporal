package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.entity.*;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.service.InventoryService;
import com.supplychainmanagement.service.OrderHandlingService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderHandlingServiceImpl implements OrderHandlingService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ReservationRepository reservationRepository;
    private final InventoryService inventoryService;

    /**
     * A projection, not entities: the page is one query and carries nothing lazy into the response.
     * See {@link PickingOrderDto} for why the endpoint stopped handing out the Reservation itself.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PickingOrderDto> pickingOrders(ReservationStatus reservationStatus, Pageable pageable) {
        return reservationRepository.findPickingOrders(reservationStatus, pageable);
    }

    /**
     * Picks the single reservation with that id. Which reservation is meant is the only thing this
     * method decides - the picking itself is {@link #pickingReservation}.
     */
    @Override
    public PickingOrderDto pickingReservationById(Long reservationId) {
        var reservation = reservationRepository.findByIdAndStatus(reservationId, ReservationStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", "id maybe already converted", reservationId));
        Order order = findOrder(reservation.getOrderId());

        var reservationAfterPicking = pickingReservation(order, reservation);
        return toDto(order, reservationAfterPicking);
    }


    /**
     * Picks every active reservation of the order. Not idempotent per call the way reserving is:
     * a reservation already consumed is no longer ACTIVE and simply does not turn up again.
     * <p>
     * A failure on one line aborts the whole loop - the lines picked before it stay picked, since
     * each of them was written in its own transaction.
     */
    @Override
    public List<PickingOrderDto> pickingReservationByOrderNo(String orderNo) {
        Order order = findOrder(orderNo);

        // Queried with the order's id, not with the orderNo that came in: Reservation.orderId holds
        // the numeric Order.id as a String, so passing the orderNo through would find nothing for
        // every order whose two numbers differ.
        return reservationRepository.findByOrderIdAndStatus(String.valueOf(order.getId()), ReservationStatus.ACTIVE).stream()
                .map(reservation -> toDto(order, pickingReservation(order, reservation)))
                .toList();
    }


    @Override
    public PickingOrderDto readyDispatch(Long reservationId) {
        var reservation = reservationRepository.findByIdAndStatus(reservationId, ReservationStatus.CONSUMED)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", "id not found", reservationId));

        var orderItem = reservation.getOrderItem();
        if (orderItem.getFulfillmentStatus() == FulfillmentStatus.READY_FOR_DISPATCH) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item is already in READY_FOR_DISPATCH status, cannot move to READY_FOR_DISPATCH");
        }
        if (orderItem.getFulfillmentStatus() != FulfillmentStatus.PACKED) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item is not in PACKED status, cannot move to READY_FOR_DISPATCH");
        }
        orderItem.setFulfillmentStatus(FulfillmentStatus.READY_FOR_DISPATCH);
        orderItemRepository.save(orderItem);

        return toDto(findOrder(reservation.getOrderId()), reservation);
    }

    /**
     * Maps a picked reservation to the same shape the picking list uses, so the warehouse gets one
     * row format everywhere.
     * <p>
     * Mapped here, inside the service, rather than by handing the entity to Jackson: the response
     * writer would resolve the LAZY references itself - a query each, and the chain
     * OrderItem -&gt; Order -&gt; orderItems closes into a cycle it cannot get out of. The two lazy
     * reads this costs happen once per picked line and are bounded by the order's size.
     */
    private PickingOrderDto toDto(Order order, Reservation reservation) {
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

    /**
     * The actual picking of one reservation, shared by both entry points above: the line item goes
     * to PICKING, the reserved stock is consumed, the reservation becomes CONSUMED, and only then
     * does the line item reach PICKED.
     * <p>
     * The order is handed in rather than looked up from {@code reservation.getOrderId()}: picking a
     * whole order would otherwise repeat the same lookup for every single line.
     */
    private Reservation pickingReservation(Order order, Reservation reservation) {
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

        return newReservation;
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

    /**
     * {@code Reservation.orderId} is a String while {@code Order.id} is numeric - the conversion and
     * the lookup live in one place instead of at every call site. Falls back to the orderNo so both
     * identifiers reach the same order.
     */
    private Order findOrder(String orderId) {
        Long id = Long.valueOf(orderId);

        return orderRepository.findById(id)
                .orElseGet(() -> orderRepository.findByOrderNo(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Order", "id", id)));
    }
}
