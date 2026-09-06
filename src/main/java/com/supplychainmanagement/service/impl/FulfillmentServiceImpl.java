package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.fullfillment.AvailableOrderItemDto;
import com.supplychainmanagement.dto.fullfillment.ProductionResultDto;
import com.supplychainmanagement.dto.reservation.ReservationOutcome;
import com.supplychainmanagement.dto.reservation.ReservationResult;
import com.supplychainmanagement.dto.reservation.ReservationSummary;
import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.entity.*;
import com.supplychainmanagement.event.OrderStatusChangedEvent;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.exception.UnsufficientException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.repository.*;
import com.supplychainmanagement.service.FulfillmentService;
import com.supplychainmanagement.service.InventoryService;
import com.supplychainmanagement.service.OrderService;
import com.supplychainmanagement.service.ProductionService;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FulfillmentServiceImpl implements FulfillmentService {

    private static final Set<OrderStatus> PRE_FULFILLMENT_STATUSES = EnumSet.of(
            OrderStatus.CREATED, OrderStatus.ACKNOWLEDGED, OrderStatus.REVIEW, OrderStatus.APPROVED);


    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final ReservationRepository reservationRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final ProductionService productionService;

    /**
     * Transactional so the order status, the line item statuses and the published
     * {@link OrderStatusChangedEvent} share one commit.
     * <p>
     * Note that the stock side does NOT roll back with it: {@code reserveWithRetry} delegates to
     * {@code InventoryReservationTransactionService.reserve}, which runs REQUIRES_NEW and therefore
     * commits on its own. That is deliberate - the idempotency guard and the retry loop depend on
     * seeing committed state - but it means a failure after the reservation leaves the stock
     * reserved while the order stays untouched. The idempotency guard makes a repeat call safe.
     * <p>
     * Reports only the reservations this call created. What was already reserved is left out on
     * purpose: a repeated call is a no-op and has nothing to show for itself. The
     * {@link ReservationOutcome} tells the caller whether an empty result means "done" or
     * "still waiting for stock".
     */
    @Override
    @Transactional
    public ReservationSummary reserveItems(Order order, String username) {
        List<AvailableOrderItemDto> availableItems = productionService.checkItems(order);

        // Only lines that a single storehouse can cover are handed to the reservation. The rest are
        // left out entirely: passing them on would mean sending storehouseId == null into
        // InventoryReservationTransactionService, which cannot match a stock row for it. They stay
        // WAITING and are retried on the next call.
        List<AvailableOrderItemDto> unavailableItems = new ArrayList<>();
        List<ReserveItem> reserveItems = new ArrayList<>();
        for (AvailableOrderItemDto checkItem : availableItems) {
            if (!checkItem.available()) {
                unavailableItems.add(checkItem);
                continue;
            }

            var productOptional = productRepository.findByArticleNo(checkItem.articleNo());
            if (productOptional.isEmpty()) {
                throw new IllegalArgumentException("Product not found: " + checkItem.articleNo());
            }

            var product = productOptional.get();
            if (product.getSku() == null) {
                throw new IllegalArgumentException("Product SKU is null for: " + checkItem.articleNo());
            }

            reserveItems.add(new ReserveItem(
                    checkItem.orderItemId(),
                    product.getSku(),
                    checkItem.orderQuantity(),
                    checkItem.storehouseId()));
        }

        // Nothing to reserve and nothing reserved earlier: that is a genuine failure, not a partial
        // result. A line already held by this order reads as unavailable in checkItems - its stock
        // is booked to this very order - so the existing reservations have to be consulted before
        // giving up.
        if (reserveItems.isEmpty() && findActiveReservations(order).isEmpty()) {
            throw new UnsufficientException(describeUnavailable(unavailableItems));
        }

        ReservationResult result;
        try {
            // Reserve items in inventory with retry logic
            result = inventoryService.reserveWithRetry(String.valueOf(order.getId()), reserveItems);
        } catch (Exception e) {
            // Handle reservation failure
            throw new APIException(HttpStatus.BAD_REQUEST, "Failed to reserve items for order " + order.getId() + ": " + e.getMessage());
        }

        // Driven by the active reservations, not by the ones just created: a repeat call after a
        // first one that committed its reservation but failed before the order was written has to
        // be able to catch the order status up, and it creates nothing to go by.
        Set<UUID> reservedSkus = result.active().stream()
                .map(Reservation::getSku)
                .collect(Collectors.toSet());

        // Advance the order status only AFTER a successful reservation, otherwise the order would
        // read as "in progress" even though reserveWithRetry failed. Only move forward (out of a
        // pre-fulfillment status) so that a second, idempotent call never sets a further-along order
        // back. A partial reservation counts: fulfillment has started for at least one line.
        if (!reservedSkus.isEmpty() && PRE_FULFILLMENT_STATUSES.contains(order.getStatus())) {
            OrderStatus previousStatus = order.getStatus();
            order.setStatus(OrderStatus.IN_FULFILLMENT);
            orderRepository.save(order);
            userRepository.findByUsernameOrEmail(username, username).ifPresent(user -> {
                eventPublisher.publishEvent(new OrderStatusChangedEvent(order.getId(), user.getId(), previousStatus, OrderStatus.IN_FULFILLMENT));
            });
        }

        // Only the lines actually covered by a reservation move to RESERVED. The others keep the
        // status they had - normally WAITING - and are picked up again by the next call. Their
        // status is deliberately not reset here, so a line that is already further along in
        // fulfillment is not thrown back.
        List<OrderItem> reservedOrderItems = order.getOrderItems().stream()
                .filter(orderItem -> reservedSkus.contains(orderItem.getProduct().getSku()))
                .toList();

        reservedOrderItems.forEach(orderItem -> orderItem.setFulfillmentStatus(FulfillmentStatus.RESERVED));
        orderItemRepository.saveAll(reservedOrderItems);

        // Prevent lazy proxy access (e.g. debugger/toString outside session) on returned entities.
        List<Reservation> createdReservations = result.created().stream()
                .map(this::toDetachedReservationWithoutOrderItem)
                .toList();

        return new ReservationSummary(createdReservations, outcomeOf(order, result));
    }

    /**
     * A line still sitting in WAITING is outstanding - every line covered by this call has just been
     * advanced to RESERVED. Deliberately not measured against the active reservations: a line that
     * is already picked has had its reservation consumed, so it no longer counts as active while
     * being anything but outstanding.
     */
    private ReservationOutcome outcomeOf(Order order, ReservationResult result) {
        if (!result.created().isEmpty()) {
            return ReservationOutcome.CREATED;
        }

        boolean anyOutstanding = order.getOrderItems().stream()
                .anyMatch(orderItem -> orderItem.getFulfillmentStatus() == null
                        || orderItem.getFulfillmentStatus() == FulfillmentStatus.WAITING);

        return anyOutstanding ? ReservationOutcome.PENDING : ReservationOutcome.COMPLETE;
    }

    /**
     * Names every line that cannot be covered, together with the qty asked for. The available
     * qty is deliberately left out: for an uncovered line it is always 0 by construction, which
     * would read as "no stock at all" rather than "not enough in any one storehouse".
     */
    private String describeUnavailable(List<AvailableOrderItemDto> unavailableItems) {
        String detail = unavailableItems.stream()
                .map(checkItem -> "article " + checkItem.articleNo() + " (requested " + checkItem.orderQuantity() + ")")
                .collect(Collectors.joining(", "));

        return "No single storehouse holds the requested qty for: " + detail;
    }

    // Helper along the lines of checkItems: returns the currently active reservations for the
    // order instead of checking stock levels the way checkItems does.
    private List<Reservation> findActiveReservations(Order order) {
        return reservationRepository.findActive(String.valueOf(order.getId()));
    }

    private Reservation toDetachedReservationWithoutOrderItem(Reservation source) {
        Reservation copy = new Reservation();
        copy.setId(source.getId());
        copy.setOrderId(source.getOrderId());
        copy.setSku(source.getSku());
        copy.setQuantity(source.getQuantity());
        copy.setStatus(source.getStatus());
        copy.setStorehouse(source.getStorehouse());
        copy.setExpiresAt(source.getExpiresAt());
        copy.setOrderId(source.getOrderId());
        copy.setOrderItem(null); // Detach the order item to avoid lazy loading issues
        return copy;
    }

    /**
     * Transactional for the same reason as {@link #reserveItems}: the line item statuses must be
     * written as one unit. The stock release itself again runs REQUIRES_NEW and commits separately.
     */
    @Override
    @Transactional
    public void releaseItems(Order order) {
        List<Reservation> activeReservations = findActiveReservations(order);
        if (activeReservations.isEmpty()) {
            throw new ResourceNotFoundException("Reservation", "orderId", order.getOrderNo());
        }

        // getOrderItem().getId() does not initialize the proxy - Hibernate serves the id out of it.
        List<ReserveItem> items = activeReservations.stream()
                .map(reservation -> new ReserveItem(
                        reservation.getOrderItem().getId(),
                        reservation.getSku(),
                        reservation.getQuantity(),
                        reservation.getStorehouse().getId()))
                .toList();

        inventoryService.releaseWithRetry(String.valueOf(order.getId()), items);

        // Reset the line items to WAITING only AFTER the release actually succeeded. Writing the
        // status first would leave them as WAITING even when releaseWithRetry threw - the items
        // would read as unreserved while their stock is still held by an active reservation.
        List<OrderItem> releasedOrderItems = order.getOrderItems().stream()
                .filter(orderItem -> activeReservations.stream()
                        .anyMatch(reservation -> reservation.getSku().equals(orderItem.getProduct().getSku())))
                .toList();

        releasedOrderItems.forEach(orderItem -> orderItem.setFulfillmentStatus(FulfillmentStatus.WAITING));
        orderItemRepository.saveAll(releasedOrderItems);
    }
}
