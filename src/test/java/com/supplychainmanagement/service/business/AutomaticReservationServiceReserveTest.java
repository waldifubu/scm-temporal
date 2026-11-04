package com.supplychainmanagement.service.business;

import com.supplychainmanagement.dto.reservation.ReservationOutcome;
import com.supplychainmanagement.dto.reservation.ReservationSummary;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.service.FulfillmentService;
import com.supplychainmanagement.service.OrderService;
import com.supplychainmanagement.service.ProductionService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which orders {@code tryToReserve} looks at. An order is computed from the shipments its lines travel in, so one
 * with a line still WAITING can read READY_FOR_DISPATCH, IN_TRANSIT or PARTIALLY_DELIVERED (order 4567 did): left
 * out of the sweep, that line was never reserved and the order never reached DELIVERED.
 */
class AutomaticReservationServiceReserveTest {

    private final FulfillmentService fulfillment = mock(FulfillmentService.class);
    private final OrderService orders = mock(OrderService.class);
    private final AutomaticReservationService service =
            new AutomaticReservationService(fulfillment, orders, mock(ProductionService.class));

    {
        when(fulfillment.reserveItems(any(Order.class), any()))
                .thenReturn(new ReservationSummary(List.of(), ReservationOutcome.COMPLETE));
    }

    private Order order(OrderStatus status) {
        Order order = new Order();
        order.setStatus(status);
        return order;
    }

    private Page<Order> page(Order... content) {
        return new PageImpl<>(List.of(content));
    }

    @Test
    void reservesForEveryStatusThatMayHoldAWaitingLine() {
        for (OrderStatus status : AutomaticReservationService.RESERVABLE_STATUSES) {
            Order order = order(status);
            when(orders.findAllByStatus(eq(status), any(Pageable.class))).thenReturn(page(order));
        }
        when(orders.findAllByStatus(eq(OrderStatus.CREATED), any(Pageable.class))).thenReturn(page());

        service.tryToReserve();

        verify(fulfillment, org.mockito.Mockito.times(AutomaticReservationService.RESERVABLE_STATUSES.size()))
                .reserveItems(any(Order.class), eq("system"));
    }

    @Test
    void aPartiallyDeliveredOrderIsReserved() {
        Order partly = order(OrderStatus.PARTIALLY_DELIVERED);
        when(orders.findAllByStatus(any(OrderStatus.class), any(Pageable.class))).thenReturn(page());
        when(orders.findAllByStatus(eq(OrderStatus.PARTIALLY_DELIVERED), any(Pageable.class))).thenReturn(page(partly));

        service.tryToReserve();

        verify(fulfillment).reserveItems(partly, "system");
    }

    @Test
    void endedAndEarlyOrdersAreNotAsked() {
        when(orders.findAllByStatus(any(OrderStatus.class), any(Pageable.class))).thenReturn(page());

        service.tryToReserve();

        for (OrderStatus status : List.of(OrderStatus.CREATED, OrderStatus.DELIVERED, OrderStatus.COMPLETED,
                OrderStatus.CANCELLED, OrderStatus.REJECTED)) {
            verify(orders, never()).findAllByStatus(eq(status), any(Pageable.class));
        }
    }
}
