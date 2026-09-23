package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.event.OrderStatusChangedEvent;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How an order follows what happens around it: forwards through the chain, and the one way back from
 * a dispatch that was called off. Every change has to publish an event - that is what writes the
 * OrderHistory row.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderProgressServiceImplTest {

    private static final Long ORDER_ID = 1042L;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OrderProgressServiceImpl service;

    private Order order(OrderStatus status) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setStatus(status);
        when(orderRepository.findAllById(anyCollection())).thenReturn(List.of(order));
        when(orderRepository.findWithOrderItemsByIdIn(anyCollection())).thenReturn(List.of(order));
        return order;
    }

    private static OrderItem line(FulfillmentStatus status) {
        OrderItem line = new OrderItem();
        line.setFulfillmentStatus(status);
        return line;
    }

    private OrderStatusChangedEvent published() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return (OrderStatusChangedEvent) captor.getValue();
    }

    @Test
    void movesAnOrderForwardAndSaysSo() {
        Order order = order(OrderStatus.IN_FULFILLMENT);

        service.advance(List.of(ORDER_ID), OrderStatus.READY_FOR_DISPATCH, 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_DISPATCH);
        verify(orderRepository).save(order);
        OrderStatusChangedEvent event = published();
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.userId()).isEqualTo(99L);
        assertThat(event.previousStatus()).isEqualTo(OrderStatus.IN_FULFILLMENT);
        assertThat(event.newStatus()).isEqualTo(OrderStatus.READY_FOR_DISPATCH);
    }

    /** Already there, or past it: a repeated report changes nothing. */
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"READY_FOR_DISPATCH", "IN_TRANSIT", "DELIVERED", "COMPLETED"})
    void neverMovesAnOrderBackwards(OrderStatus current) {
        Order order = order(current);

        service.advance(List.of(ORDER_ID), OrderStatus.READY_FOR_DISPATCH, 99L);

        assertThat(order.getStatus()).isEqualTo(current);
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** An order that ended is not part of the chain any more. */
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"REJECTED", "CANCELLED"})
    void leavesAnOrderThatEndedAlone(OrderStatus current) {
        Order order = order(current);

        service.advance(List.of(ORDER_ID), OrderStatus.IN_TRANSIT, 99L);

        assertThat(order.getStatus()).isEqualTo(current);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void asksForNothingWithoutOrders() {
        service.advance(List.of(), OrderStatus.IN_TRANSIT, 99L);
        service.takeBackFromDispatch(List.of(), 99L);

        verify(orderRepository, never()).findAllById(any());
        verify(orderRepository, never()).findWithOrderItemsByIdIn(any());
    }

    /** The way back: no line of the order is on its way any more. */
    @Test
    void takesAnOrderBackWhenNoLineIsOnItsWay() {
        Order order = order(OrderStatus.READY_FOR_DISPATCH);
        order.setOrderItems(new LinkedHashSet<>(List.of(line(FulfillmentStatus.PACKED))));

        service.takeBackFromDispatch(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_FULFILLMENT);
        assertThat(published().newStatus()).isEqualTo(OrderStatus.IN_FULFILLMENT);
    }

    /** One line still READY_FOR_DISPATCH travels in another shipment - the order stays. */
    @Test
    void keepsAnOrderWithALineStillOnItsWay() {
        Order order = order(OrderStatus.READY_FOR_DISPATCH);
        order.setOrderItems(new LinkedHashSet<>(List.of(
                line(FulfillmentStatus.PACKED), line(FulfillmentStatus.READY_FOR_DISPATCH))));

        service.takeBackFromDispatch(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_DISPATCH);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ------------------------------------------------------------------ the primitive everyone shares

    /**
     * What every caller that changes an order status goes through: written, saved and published in
     * one place. No rule about the direction here - a rejection leaves the flow altogether, and the
     * caller is the one who decides whether the step is allowed.
     */
    @Test
    void changeStatusWritesTheStatusAndRecordsIt() {
        Order order = order(OrderStatus.CREATED);

        service.changeStatus(order, OrderStatus.REJECTED, 7L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        verify(orderRepository).save(order);
        OrderStatusChangedEvent event = published();
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.userId()).isEqualTo(7L);
        assertThat(event.previousStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(event.newStatus()).isEqualTo(OrderStatus.REJECTED);
    }

    /** A write that changes nothing would read in the history as a step that never happened. */
    @Test
    void changeStatusIgnoresTheStatusTheOrderAlreadyHas() {
        Order order = order(OrderStatus.APPROVED);

        service.changeStatus(order, OrderStatus.APPROVED, 7L);

        verify(orderRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * A request that names no status leaves the order where it is. The CRUD update used to write the
     * null through, which took the status off an order over a field the caller never sent.
     */
    @Test
    void changeStatusIgnoresAMissingTarget() {
        Order order = order(OrderStatus.APPROVED);

        service.changeStatus(order, null, 7L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.APPROVED);
        verify(orderRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** The first row of an order's history: no previous status, and nothing left to write. */
    @Test
    void recordCreatedPublishesWithoutAPreviousStatus() {
        Order order = order(OrderStatus.CREATED);

        service.recordCreated(order, 3L);

        OrderStatusChangedEvent event = published();
        assertThat(event.previousStatus()).isNull();
        assertThat(event.newStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(event.userId()).isEqualTo(3L);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void recordCreatedSaysNothingAboutAnOrderWithoutAStatus() {
        service.recordCreated(order(null), 3L);

        verify(eventPublisher, never()).publishEvent(any());
    }

    /** Only from READY_FOR_DISPATCH: an order already in transit is not pulled back. */
    @Test
    void takesBackNothingFromAnOrderInTransit() {
        Order order = order(OrderStatus.IN_TRANSIT);
        order.setOrderItems(new LinkedHashSet<>(List.of(line(FulfillmentStatus.PACKED))));

        service.takeBackFromDispatch(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_TRANSIT);
        verify(eventPublisher, never()).publishEvent(any());
    }
}
