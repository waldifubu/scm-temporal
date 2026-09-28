package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.order.ShippedQuantity;
import com.supplychainmanagement.dto.order.UndeliveredLine;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.event.OrderStatusChangedEvent;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where an order really stands. The status is not pushed onto it by whichever shipment reports in -
 * it is worked out from the quantities of its lines that sit in shipments, and every change has to
 * publish an event, because that is what writes the OrderHistory row.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderProgressServiceImplTest {

    private static final Long ORDER_ID = 1042L;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OrderProgressServiceImpl service;

    /** An order in the given status, carrying the given lines, as the batch query returns it. */
    private Order order(OrderStatus status, OrderItem... lines) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setStatus(status);
        order.setOrderItems(new LinkedHashSet<>(List.of(lines)));
        when(orderRepository.findWithOrderItemsByIdIn(anyCollection())).thenReturn(List.of(order));
        return order;
    }

    private static OrderItem line(Long id, int quantity) {
        OrderItem line = new OrderItem();
        line.setId(id);
        line.setQuantity(quantity);
        return line;
    }

    /** What the quantity query reports for this order - one row per line and shipment status. */
    private void shipped(ShippedQuantity... quantities) {
        when(orderItemRepository.findShippedQuantities(anyCollection())).thenReturn(List.of(quantities));
    }

    private static ShippedQuantity in(Long orderItemId, ShipmentStatus status, long quantity) {
        return new ShippedQuantity(ORDER_ID, orderItemId, status, quantity);
    }

    private OrderStatusChangedEvent published() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return (OrderStatusChangedEvent) captor.getValue();
    }

    // ------------------------------------------------------------------ what the status is worked out from

    /** Nothing of the order has been handed over yet - it is still being fulfilled. */
    @Test
    void staysInFulfillmentWhileNothingHasBeenHandedOver() {
        Order order = order(OrderStatus.IN_FULFILLMENT, line(11L, 10));
        shipped();

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_FULFILLMENT);
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** Every line is in a shipment the warehouse has reported ready. */
    @Test
    void readyForDispatchOnceEveryLineIsInAHandedOverShipment() {
        Order order = order(OrderStatus.IN_FULFILLMENT, line(11L, 10));
        shipped(in(11L, ShipmentStatus.READY, 10));

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_DISPATCH);
        assertThat(published().newStatus()).isEqualTo(OrderStatus.READY_FOR_DISPATCH);
    }

    /**
     * The bug this whole mechanism replaces: one line of three is in a ready shipment, the rest is
     * still in the warehouse. Pushing a fixed target moved the order anyway.
     */
    @Test
    void doesNotReportAnOrderReadyWhileOtherLinesAreStillInTheWarehouse() {
        Order order = order(OrderStatus.IN_FULFILLMENT, line(11L, 10), line(12L, 3));
        shipped(in(11L, ShipmentStatus.READY, 10));

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_FULFILLMENT);
    }

    /** Half a line handed over is not a handed-over line - the quantity decides, not the row. */
    @Test
    void countsQuantitiesAndNotLines() {
        Order order = order(OrderStatus.IN_FULFILLMENT, line(11L, 10));
        shipped(in(11L, ShipmentStatus.READY, 4));

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_FULFILLMENT);
    }

    /** A line split over two shipments counts once both halves are there. */
    @Test
    void addsUpTheHalvesOfALineAcrossShipments() {
        Order order = order(OrderStatus.IN_FULFILLMENT, line(11L, 10));
        shipped(in(11L, ShipmentStatus.READY, 4), in(11L, ShipmentStatus.ACCEPTED, 6));

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_DISPATCH);
    }

    /** Everything on the road, nothing arrived. */
    @Test
    void inTransitOnceEveryLineHasLeft() {
        Order order = order(OrderStatus.READY_FOR_DISPATCH, line(11L, 10));
        shipped(in(11L, ShipmentStatus.IN_TRANSIT, 10));

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_TRANSIT);
    }

    /** Everything arrived. */
    @Test
    void deliveredOnceEveryLineHasArrived() {
        Order order = order(OrderStatus.IN_TRANSIT, line(11L, 10), line(12L, 3));
        shipped(in(11L, ShipmentStatus.DELIVERED, 10), in(12L, ShipmentStatus.DELIVERED, 3));

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    /** Something arrived, something is still out - and that is the one case the new status is for. */
    @Test
    void partiallyDeliveredWhenOneShipmentArrivedAndAnotherIsStillOut() {
        Order order = order(OrderStatus.IN_TRANSIT, line(11L, 10), line(12L, 3));
        shipped(in(11L, ShipmentStatus.DELIVERED, 10), in(12L, ShipmentStatus.IN_TRANSIT, 3));

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_DELIVERED);
    }

    /** Half a line arrived, the other half is still on the road. */
    @Test
    void partiallyDeliveredWhenOneLineIsOnlyHalfThere() {
        Order order = order(OrderStatus.IN_TRANSIT, line(11L, 10));
        shipped(in(11L, ShipmentStatus.DELIVERED, 4), in(11L, ShipmentStatus.IN_TRANSIT, 6));

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_DELIVERED);
    }

    /**
     * PARTIALLY_DELIVERED belongs to the delivery alone: part of the order on the road while the rest
     * is still being picked is not "partly" anything, it is still being fulfilled.
     */
    @Test
    void doesNotCallAnOrderPartlyDeliveredJustBecauseAShipmentLeft() {
        Order order = order(OrderStatus.IN_FULFILLMENT, line(11L, 10), line(12L, 3));
        shipped(in(11L, ShipmentStatus.IN_TRANSIT, 10));

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_FULFILLMENT);
    }

    /**
     * The trap the whole thing is built around: every join of the query is an inner one, so a line
     * nothing has shipped for is not in the result at all. Read on its own it would look complete.
     */
    @Test
    void countsALineNothingHasShippedForAsMissing() {
        Order order = order(OrderStatus.IN_TRANSIT, line(11L, 10), line(12L, 3));
        shipped(in(11L, ShipmentStatus.DELIVERED, 10));

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_DELIVERED);
    }

    /**
     * An order with no lines would be covered by everything - allMatch says true for none - and would
     * walk straight to DELIVERED.
     */
    @Test
    void leavesAnOrderWithoutLinesInFulfillment() {
        Order order = order(OrderStatus.IN_FULFILLMENT);
        shipped();

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_FULFILLMENT);
    }

    // ------------------------------------------------------------------ what it does not touch

    /**
     * Only the stretch the shipment side owns. Before it the order is the order's own business -
     * WAIT_SUPPLY is about stock, not about shipments - and the three ends are not steps at all. A
     * status outside the range is skipped deliberately, not as the side effect of a lookup.
     */
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class,
            names = {"CREATED", "ACKNOWLEDGED", "REVIEW", "APPROVED", "WAIT_SUPPLY", "REJECTED", "CANCELLED", "COMPLETED"})
    void leavesAnOrderOutsideTheShipmentRangeAlone(OrderStatus status) {
        Order order = order(status, line(11L, 10));
        shipped(in(11L, ShipmentStatus.DELIVERED, 10));

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(status);
        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * A cancelled shipment hands its packages back, so the coverage drops and the order falls back by
     * itself - this is what used to be takeBackFromDispatch.
     */
    @Test
    void fallsBackWhenTheCoverageIsGoneAgain() {
        Order order = order(OrderStatus.READY_FOR_DISPATCH, line(11L, 10));
        shipped();

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_FULFILLMENT);
        assertThat(published().previousStatus()).isEqualTo(OrderStatus.READY_FOR_DISPATCH);
    }

    /** A line of the order still travelling in another shipment keeps the order where it is. */
    @Test
    void doesNotFallBackWhileAnotherShipmentStillCarriesALine() {
        Order order = order(OrderStatus.READY_FOR_DISPATCH, line(11L, 10));
        shipped(in(11L, ShipmentStatus.ACCEPTED, 10));

        service.recompute(List.of(ORDER_ID), 99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_DISPATCH);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void asksForNothingWithoutOrders() {
        service.recompute(List.of(), 99L);

        verify(orderRepository, never()).findWithOrderItemsByIdIn(any());
        verify(orderItemRepository, never()).findShippedQuantities(any());
    }

    // ------------------------------------------------------------------ what a completion is refused with

    /** What is short, and by how much - that is what the refusal of a completion is built from. */
    @Test
    void undeliveredLinesNamesOrderedAgainstArrived() {
        Order order = order(OrderStatus.PARTIALLY_DELIVERED, line(11L, 10), line(12L, 3));
        shipped(in(11L, ShipmentStatus.DELIVERED, 4), in(12L, ShipmentStatus.IN_TRANSIT, 3));

        assertThat(service.undeliveredLines(order))
                .extracting(UndeliveredLine::orderItemId, UndeliveredLine::ordered, UndeliveredLine::delivered)
                .containsExactly(tuple(11L, 10, 4L), tuple(12L, 3, 0L));
    }

    /** Only what really arrived counts - a line on the road is not a delivered line. */
    @Test
    void undeliveredLinesIsEmptyOnlyWhenEverythingArrived() {
        Order order = order(OrderStatus.PARTIALLY_DELIVERED, line(11L, 10));
        shipped(in(11L, ShipmentStatus.DELIVERED, 10));

        assertThat(service.undeliveredLines(order)).isEmpty();
    }

    /**
     * An order without lines has nothing that could be missing. That is not "everything arrived" -
     * whoever asks has to refuse such an order before it gets here, which OrderService.complete does.
     */
    @Test
    void undeliveredLinesIsEmptyForAnOrderWithoutLines() {
        Order order = order(OrderStatus.IN_TRANSIT);

        assertThat(service.undeliveredLines(order)).isEmpty();
    }

    // ------------------------------------------------------------------ the primitive everyone shares

    /**
     * What every caller that changes an order status goes through: written, saved and published in
     * one place. No rule about the direction here - a rejection leaves the chain altogether, and the
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
}
