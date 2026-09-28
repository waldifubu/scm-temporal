package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.order.UndeliveredLine;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.OrderProgressService;
import com.supplychainmanagement.service.ProductionService;
import com.supplychainmanagement.service.RoleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
 * Closing an order. What counts as delivered is OrderProgressService's answer - checked in its own
 * test; what is checked here is that nothing closes without it, and that the refusal says why.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceCompleteTest {

    private static final Long ORDER_ID = 42L;

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

    @InjectMocks
    private OrderServiceImpl service;

    /** An order in the given status with one line, and nothing missing unless a test says so. */
    private Order order(OrderStatus status) {
        OrderItem line = new OrderItem();
        line.setId(11L);
        line.setQuantity(10);

        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo(4711L);
        order.setStatus(status);
        order.setOrderItems(new LinkedHashSet<>(List.of(line)));

        when(orderProgress.undeliveredLines(order)).thenReturn(List.of());
        when(orderRepository.findWithDetailsById(ORDER_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private static void assertStatus(Throwable thrown, HttpStatus status) {
        assertThat(thrown).isInstanceOfSatisfying(APIException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    /**
     * Everything has arrived, so the order closes - through OrderProgressService, which is what
     * writes the audit row.
     */
    @Test
    void closesAnOrderThatHasFullyArrived() {
        Order order = order(OrderStatus.DELIVERED);

        service.complete(order, 99L);

        verify(orderProgress).changeStatus(order, OrderStatus.COMPLETED, 99L);
    }

    /**
     * The order status is not the precondition - it is set by whichever shipment arrives first and
     * therefore says less than the quantities do. An order still reading PARTIALLY_DELIVERED whose
     * last package has meanwhile arrived closes just as well.
     */
    @Test
    void doesNotAskForTheOrderStatusToSayDelivered() {
        Order order = order(OrderStatus.PARTIALLY_DELIVERED);

        service.complete(order, 99L);

        verify(orderProgress).changeStatus(order, OrderStatus.COMPLETED, 99L);
    }

    /** A line that is still short blocks it, and the message says which one and by how much. */
    @Test
    void refusesAnOrderThatIsStillMissingSomething() {
        Order order = order(OrderStatus.PARTIALLY_DELIVERED);
        when(orderProgress.undeliveredLines(order)).thenReturn(List.of(new UndeliveredLine(11L, 10, 4L)));

        Throwable thrown = catchThrowable(() -> service.complete(order, 99L));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("line 11: 4 of 10 delivered");
        verify(orderProgress, never()).changeStatus(any(), any(), any());
    }

    /** Closing twice is not a no-op but a mistake - the order is already closed. */
    @Test
    void refusesAnOrderThatIsAlreadyCompleted() {
        Order order = order(OrderStatus.COMPLETED);

        assertStatus(catchThrowable(() -> service.complete(order, 99L)), HttpStatus.CONFLICT);

        verify(orderProgress, never()).changeStatus(any(), any(), any());
    }

    /**
     * An order that ended is not closed, it is over. Without this guard changeStatus would happily
     * write COMPLETED over it, and advance would have skipped it silently - a 200 for nothing.
     */
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"REJECTED", "CANCELLED"})
    void refusesAnOrderThatEnded(OrderStatus status) {
        Order order = order(status);

        Throwable thrown = catchThrowable(() -> service.complete(order, 99L));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("cannot be completed");
        verify(orderProgress, never()).changeStatus(any(), any(), any());
    }

    /**
     * Nothing ordered is not everything delivered: with no lines there is nothing that could be
     * short, so the order would close on the spot.
     */
    @Test
    void refusesAnOrderWithoutLines() {
        Order order = order(OrderStatus.DELIVERED);
        order.setOrderItems(new LinkedHashSet<>());

        Throwable thrown = catchThrowable(() -> service.complete(order, 99L));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("has no lines");
        verify(orderProgress, never()).changeStatus(any(), any(), any());
        // Not even asked - the check would have answered "nothing missing".
        verify(orderProgress, never()).undeliveredLines(any());
    }
}
