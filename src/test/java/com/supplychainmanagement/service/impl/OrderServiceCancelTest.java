package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.FulfillmentService;
import com.supplychainmanagement.service.OrderProgressService;
import com.supplychainmanagement.service.ProductionService;
import com.supplychainmanagement.service.RoleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cancelling an order. Where the boundary is - nothing may have physically moved - and that what the
 * order held really goes back.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceCancelTest {

    private static final Long ORDER_ID = 42L;
    private static final String USERNAME = "manager";

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private RoleService roleService;
    @Mock
    private ProductionService productionService;
    @Mock
    private OrderProgressService orderProgress;
    @Mock
    private FulfillmentService fulfillmentService;

    @InjectMocks
    private OrderServiceImpl service;

    private static OrderItem line(Long id, FulfillmentStatus status) {
        OrderItem line = new OrderItem();
        line.setId(id);
        line.setQuantity(3);
        line.setFulfillmentStatus(status);
        return line;
    }

    /** An order in the given status with one reserved line, and nothing off the shelf. */
    private Order order(OrderStatus status, OrderItem... lines) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo(4711L);
        order.setStatus(status);
        order.setOrderItems(new LinkedHashSet<>(List.of(lines)));

        when(fulfillmentService.linesPastReservation(order)).thenReturn(List.of());
        when(orderRepository.findWithDetailsById(ORDER_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private static void assertStatus(Throwable thrown, HttpStatus status) {
        assertThat(thrown).isInstanceOfSatisfying(APIException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    /** Nothing has moved, so the stock goes back and the order ends. */
    @Test
    void cancelsAnOrderThatOnlyHoldsReservations() {
        Order order = order(OrderStatus.IN_FULFILLMENT, line(11L, FulfillmentStatus.RESERVED));

        service.cancel(order, USERNAME, 99L);

        verify(orderProgress).changeStatus(order, OrderStatus.CANCELLED, 99L);
        verify(fulfillmentService).releaseItems(order, USERNAME);
    }

    /**
     * The status is written before the release, and that is not cosmetic: releaseItems takes an
     * IN_FULFILLMENT order to APPROVED on its way out, so the other order would leave a step in the
     * history that never happened.
     */
    @Test
    void writesTheStatusBeforeHandingTheStockBack() {
        Order order = order(OrderStatus.IN_FULFILLMENT, line(11L, FulfillmentStatus.RESERVED));

        service.cancel(order, USERNAME, 99L);

        InOrder inOrder = Mockito.inOrder(orderProgress, fulfillmentService);
        inOrder.verify(orderProgress).changeStatus(order, OrderStatus.CANCELLED, 99L);
        inOrder.verify(fulfillmentService).releaseItems(order, USERNAME);
    }

    /** Every line ends on CANCELLED - releaseItems leaves them on WAITING, which is not an end. */
    @Test
    void endsEveryLineOnCancelled() {
        OrderItem first = line(11L, FulfillmentStatus.RESERVED);
        OrderItem second = line(12L, FulfillmentStatus.WAITING);
        Order order = order(OrderStatus.IN_FULFILLMENT, first, second);

        service.cancel(order, USERNAME, 99L);

        assertThat(first.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.CANCELLED);
        assertThat(second.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.CANCELLED);
    }

    /** Nothing reserved at all is the easy case - an order nobody has started on. */
    @Test
    void cancelsAnOrderThatHoldsNothing() {
        Order order = order(OrderStatus.CREATED, line(11L, FulfillmentStatus.WAITING));

        service.cancel(order, USERNAME, 99L);

        verify(orderProgress).changeStatus(order, OrderStatus.CANCELLED, 99L);
    }

    /**
     * The boundary: a picked line means the goods are off the shelf, its reservation is CONSUMED, and
     * booking it back in is not something this application can do. The message names the lines,
     * because only a human can sort that out.
     */
    @Test
    void refusesAnOrderWhoseGoodsHaveLeftTheShelf() {
        Order order = order(OrderStatus.IN_FULFILLMENT, line(11L, FulfillmentStatus.PICKED));
        when(fulfillmentService.linesPastReservation(order))
                .thenReturn(List.of(line(11L, FulfillmentStatus.PICKED)));

        Throwable thrown = catchThrowable(() -> service.cancel(order, USERNAME, 99L));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("11 (PICKED)");
        verify(orderProgress, never()).changeStatus(any(), any(), any());
        verify(fulfillmentService, never()).releaseItems(any(), any());
    }

    /** Once the packages are in a shipment it is the shipment that gets called off. */
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class,
            names = {"READY_FOR_DISPATCH", "IN_TRANSIT", "PARTIALLY_DELIVERED", "DELIVERED"})
    void refusesAnOrderWhosePackagesAreInAShipment(OrderStatus status) {
        Order order = order(status, line(11L, FulfillmentStatus.READY_FOR_DISPATCH));

        Throwable thrown = catchThrowable(() -> service.cancel(order, USERNAME, 99L));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("call that off instead");
        verify(fulfillmentService, never()).releaseItems(any(), any());
    }

    /** An order that ended is not cancelled, it is over. */
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"REJECTED", "COMPLETED"})
    void refusesAnOrderThatEnded(OrderStatus status) {
        Order order = order(status, line(11L, FulfillmentStatus.WAITING));

        Throwable thrown = catchThrowable(() -> service.cancel(order, USERNAME, 99L));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("cannot be cancelled");
    }

    @Test
    void refusesAnOrderThatIsAlreadyCancelled() {
        Order order = order(OrderStatus.CANCELLED, line(11L, FulfillmentStatus.CANCELLED));

        Throwable thrown = catchThrowable(() -> service.cancel(order, USERNAME, 99L));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("already cancelled");
        verify(fulfillmentService, never()).releaseItems(any(), any());
    }
}
