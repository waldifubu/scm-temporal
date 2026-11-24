package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.order.ShippedQuantity;
import com.supplychainmanagement.dto.order.UndeliveredLine;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.event.OrderStatusChangedEvent;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.OrderAction;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.service.OrderWorkflow;
import org.springframework.http.HttpStatus;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.service.OrderProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderProgressServiceImpl implements OrderProgressService {

    /** A shipment from here on has left the warehouse's hands - what READY_FOR_DISPATCH reports. */
    private static final Set<ShipmentStatus> HANDED_OVER = EnumSet.of(
            ShipmentStatus.READY, ShipmentStatus.DISPATCH_REQUESTED, ShipmentStatus.ACCEPTED,
            ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED);

    /** A shipment from here on is physically on the road, or past it. */
    private static final Set<ShipmentStatus> ON_THE_ROAD = EnumSet.of(
            ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED);

    private static final Set<ShipmentStatus> ARRIVED = EnumSet.of(ShipmentStatus.DELIVERED);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ApplicationEventPublisher eventPublisher;
    /**
     * The stretch of the chain the shipment side owns is {@code SHIPMENT_PROGRESS} here: everything before it is the
     * order's own business - WAIT_SUPPLY is about stock, not about shipments - and the ends are not steps. An order
     * outside it is left alone, in both directions. {@link #changeStatus} refuses a move the workflow does not list.
     */
    private final OrderWorkflow workflow;

    /**
     * Loaded with their lines: where an order stands depends on them, and findWithOrderItemsByIdIn
     * fetches them in one query instead of one per order. The shipped quantities follow in a second
     * query, for the whole batch at once.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recompute(Collection<Long> orderIds, Long userId) {
        if (orderIds.isEmpty()) {
            return;
        }

        Map<Long, List<ShippedQuantity>> shipped = orderItemRepository.findShippedQuantities(orderIds).stream()
                .collect(Collectors.groupingBy(ShippedQuantity::orderId));

        for (Order order : orderRepository.findWithOrderItemsByIdIn(orderIds)) {
            if (!workflow.allows(order.getStatus(), OrderAction.SHIPMENT_PROGRESS)) {
                continue;
            }
            changeStatus(order, statusOf(order, shipped.getOrDefault(order.getId(), List.of())), userId);
        }
    }

    /**
     * Where the order stands, read off its lines. Arriving is asked first: a line half delivered and
     * half on the road is not "in transit", it is partly there.
     * <p>
     * PARTIALLY_DELIVERED is reserved for the delivery. A shipment that has merely left while the
     * rest of the order is still being picked does not make the order partly anything - it keeps it
     * where the slowest line is.
     */
    private OrderStatus statusOf(Order order, List<ShippedQuantity> shipped) {
        Set<OrderItem> lines = order.getOrderItems();
        if (lines == null || lines.isEmpty()) {
            // Nothing that could travel. Without this an order with no lines would count as covered
            // by everything and walk straight to DELIVERED.
            return OrderStatus.IN_FULFILLMENT;
        }

        if (fullyCovered(lines, shipped, ARRIVED)) {
            return OrderStatus.DELIVERED;
        }
        if (coveredQuantity(shipped, ARRIVED) > 0) {
            return OrderStatus.PARTIALLY_DELIVERED;
        }
        if (fullyCovered(lines, shipped, ON_THE_ROAD)) {
            return OrderStatus.IN_TRANSIT;
        }
        if (fullyCovered(lines, shipped, HANDED_OVER)) {
            return OrderStatus.READY_FOR_DISPATCH;
        }
        return OrderStatus.IN_FULFILLMENT;
    }

    /**
     * Whether every line is completely covered by shipments in one of the given statuses - by
     * quantity, because a line may be packed in several runs and travel in several shipments, so 5 of
     * 10 covered is not a covered line.
     * <p>
     * Counted from the lines, not from the query result: a line nothing has shipped for does not
     * appear there at all - every join in that query is an inner one - so reading the result alone
     * would call such an order covered.
     */
    private static boolean fullyCovered(Set<OrderItem> lines, List<ShippedQuantity> shipped,
                                        Set<ShipmentStatus> statuses) {
        Map<Long, Long> perLine = quantitiesPerLine(shipped, statuses);

        return lines.stream().allMatch(line ->
                perLine.getOrDefault(line.getId(), 0L) >= (line.getQuantity() == null ? 0 : line.getQuantity()));
    }

    private static Map<Long, Long> quantitiesPerLine(List<ShippedQuantity> shipped, Set<ShipmentStatus> statuses) {
        return shipped.stream()
                .filter(row -> statuses.contains(row.shipmentStatus()))
                .collect(Collectors.groupingBy(ShippedQuantity::orderItemId,
                        Collectors.summingLong(ShippedQuantity::quantity)));
    }

    private static long coveredQuantity(List<ShippedQuantity> shipped, Set<ShipmentStatus> statuses) {
        return shipped.stream()
                .filter(row -> statuses.contains(row.shipmentStatus()))
                .mapToLong(ShippedQuantity::quantity)
                .sum();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UndeliveredLine> undeliveredLines(Order order) {
        Set<OrderItem> lines = order.getOrderItems();
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> delivered = quantitiesPerLine(
                orderItemRepository.findShippedQuantities(List.of(order.getId())), ARRIVED);

        // Counted from the lines, not from the query: a line nothing has arrived for is missing from
        // the result entirely, so reading the result alone would call such an order delivered.
        return lines.stream()
                .map(line -> new UndeliveredLine(line.getId(),
                        line.getQuantity() == null ? 0 : line.getQuantity(),
                        delivered.getOrDefault(line.getId(), 0L)))
                .filter(UndeliveredLine::isShort)
                .sorted(Comparator.comparing(UndeliveredLine::orderItemId))
                .toList();
    }

    /** Written and published together - the event is what writes the OrderHistory row. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void changeStatus(Order order, OrderStatus target, Long userId) {
        // A write that changes nothing would show up in the history as a step that never happened.
        if (target == null || target == order.getStatus()) {
            return;
        }

        OrderStatus previousStatus = order.getStatus();
        // Checked before anything is written: a step the workflow does not list is a bug in the caller, and the
        // history must not record it. A new order has no previous status, and recordCreated does not come here.
        if (previousStatus != null && !workflow.allowsMove(previousStatus, target)) {
            throw new APIException(HttpStatus.CONFLICT, "Order " + order.getOrderNo() + " cannot move from "
                    + previousStatus + " to " + target);
        }
        order.setStatus(target);
        orderRepository.save(order);
        eventPublisher.publishEvent(new OrderStatusChangedEvent(order.getId(), userId, previousStatus, target));
    }

    /** Nothing to write: the order is already saved, and the event carries no previous status. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCreated(Order order, Long userId) {
        if (order.getStatus() == null) {
            return;
        }

        eventPublisher.publishEvent(new OrderStatusChangedEvent(order.getId(), userId, null, order.getStatus()));
    }
}
