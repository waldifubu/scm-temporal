package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.event.OrderStatusChangedEvent;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.service.OrderProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderProgressServiceImpl implements OrderProgressService {

    /**
     * The order statuses in the sequence the process runs through. REJECTED and CANCELLED are not in
     * it: an order that ended there is never moved on from the outside.
     */
    private static final List<OrderStatus> ORDER_FLOW = List.of(
            OrderStatus.CREATED, OrderStatus.ACKNOWLEDGED, OrderStatus.REVIEW, OrderStatus.APPROVED,
            OrderStatus.IN_FULFILLMENT, OrderStatus.READY_FOR_DISPATCH, OrderStatus.IN_TRANSIT,
            OrderStatus.DELIVERED, OrderStatus.COMPLETED);

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void advance(Collection<Long> orderIds, OrderStatus target, Long userId) {
        if (orderIds.isEmpty()) {
            return;
        }

        for (Order order : orderRepository.findAllById(orderIds)) {
            if (!movesForward(order.getStatus(), target)) {
                continue;
            }
            changeStatus(order, target, userId);
        }
    }

    /**
     * Loaded with their lines: whether an order may go back is decided by them - findWithOrderItemsByIdIn
     * fetches them in one query instead of one per order.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void takeBackFromDispatch(Collection<Long> orderIds, Long userId) {
        if (orderIds.isEmpty()) {
            return;
        }

        for (Order order : orderRepository.findWithOrderItemsByIdIn(orderIds)) {
            if (order.getStatus() != OrderStatus.READY_FOR_DISPATCH) {
                continue;
            }
            boolean stillOnItsWay = order.getOrderItems().stream()
                    .anyMatch(line -> line.getFulfillmentStatus() == FulfillmentStatus.READY_FOR_DISPATCH);
            if (stillOnItsWay) {
                continue;
            }

            changeStatus(order, OrderStatus.IN_FULFILLMENT, userId);
        }
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

    /** True while the order is before the target in {@link #ORDER_FLOW}; REJECTED/CANCELLED never are. */
    private static boolean movesForward(OrderStatus current, OrderStatus target) {
        int at = ORDER_FLOW.indexOf(current);
        return at >= 0 && at < ORDER_FLOW.indexOf(target);
    }
}
