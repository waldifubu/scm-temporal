package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderHistory;
import com.supplychainmanagement.entity.users.Warehouse;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.repository.OrderHistoryRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.OrderService;
import com.supplychainmanagement.service.RoleService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The order history as a service: who may see it, and who is named in it.
 * <p>
 * The access rule is not decided here. The order is fetched through {@code findByOrderNoForUser}, the
 * call {@code GET /orders/{orderNo}} makes, so the history can never be reachable where the order is
 * not - which is what the first two tests hold.
 */
class OrderHistoryServiceImplTest {

    private static final Long ORDER_NO = 91234L;
    private static final Long ORDER_ID = 42L;

    private final OrderService orderService = mock(OrderService.class);
    private final OrderHistoryRepository historyRepository = mock(OrderHistoryRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleService roleService = mock(RoleService.class);
    private final OrderHistoryServiceImpl service =
            new OrderHistoryServiceImpl(orderService, historyRepository, userRepository, roleService);

    private final User caller = new User("caller", "x", List.of());

    private Order theOrder() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo(ORDER_NO);
        when(orderService.findByOrderNoForUser(ORDER_NO, caller)).thenReturn(order);
        return order;
    }

    private static OrderHistory row(Long id, OrderStatus previous, OrderStatus next, Long userId) {
        return OrderHistory.builder().id(id).previousStatus(previous).newStatus(next)
                .userId(userId).changedAt(LocalDateTime.of(2026, 10, 6, 9, 0).plusMinutes(id)).build();
    }

    private static Warehouse warehouseUser(Long id, String first, String last) {
        Warehouse user = new Warehouse();
        user.setId(id);
        user.setFirstName(first);
        user.setLastName(last);
        user.setUsername("login-" + id);
        return user;
    }

    // ------------------------------------------------------------------ access

    /** An order the caller may not see: the answer propagates and nothing about its history is read. */
    @Test
    void refusesWhereTheOrderItselfIsRefused() {
        when(orderService.findByOrderNoForUser(ORDER_NO, caller))
                .thenThrow(new APIException(HttpStatus.FORBIDDEN, "This order belongs to another customer"));

        Throwable thrown = catchThrowable(() -> service.findHistory(ORDER_NO, caller));

        assertThat(thrown).isInstanceOfSatisfying(APIException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(historyRepository, userRepository);
    }

    @Test
    void answersAnUnknownOrderWith404() {
        when(orderService.findByOrderNoForUser(ORDER_NO, caller))
                .thenThrow(new ResourceNotFoundException("Order", "orderNo", ORDER_NO));

        assertThat(catchThrowable(() -> service.findHistory(ORDER_NO, caller)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(historyRepository);
    }

    // ------------------------------------------------------------------ the rows

    /**
     * Read by the order's internal id, in the order the repository gives them - which is newest first,
     * so the creation, the row without a previous status, comes last.
     */
    @Test
    void keepsTheOrderTheRepositoryReturns() {
        theOrder();
        when(roleService.canReadAnyOrder(caller)).thenReturn(false);
        when(historyRepository.findByOrderIdOrderByChangedAtDescIdDesc(ORDER_ID)).thenReturn(List.of(
                row(3L, OrderStatus.ACKNOWLEDGED, OrderStatus.APPROVED, null),
                row(2L, OrderStatus.CREATED, OrderStatus.ACKNOWLEDGED, null),
                row(1L, null, OrderStatus.CREATED, null)));

        var history = service.findHistory(ORDER_NO, caller);

        assertThat(history).extracting("newStatus")
                .containsExactly(OrderStatus.APPROVED, OrderStatus.ACKNOWLEDGED, OrderStatus.CREATED);
        assertThat(history.getLast().previousStatus()).as("the creation comes from nowhere").isNull();
        assertThat(history).allSatisfy(entry -> assertThat(entry.changedAt()).isNotNull());
    }

    @Test
    void answersAnOrderWithoutHistoryAsAnEmptyList() {
        theOrder();
        when(historyRepository.findByOrderIdOrderByChangedAtDescIdDesc(ORDER_ID)).thenReturn(List.of());

        assertThat(service.findHistory(ORDER_NO, caller)).isEmpty();
    }

    // ------------------------------------------------------------------ who did it

    /** Staff see who made each change, and every name is looked up in one query. */
    @Test
    void namesTheActorsForStaffInOneQuery() {
        theOrder();
        when(roleService.canReadAnyOrder(caller)).thenReturn(true);
        when(historyRepository.findByOrderIdOrderByChangedAtDescIdDesc(ORDER_ID)).thenReturn(List.of(
                row(1L, null, OrderStatus.CREATED, 7L),
                row(2L, OrderStatus.CREATED, OrderStatus.ACKNOWLEDGED, 8L),
                row(3L, OrderStatus.ACKNOWLEDGED, OrderStatus.APPROVED, 7L)));
        when(userRepository.findAllById(Set.of(7L, 8L))).thenReturn(List.of(
                warehouseUser(7L, "Rita", "Rack"), warehouseUser(8L, null, null)));

        var history = service.findHistory(ORDER_NO, caller);

        assertThat(history).extracting("changedById").containsExactly(7L, 8L, 7L);
        // The name is the one used for a person everywhere else, the login only when there is none.
        assertThat(history).extracting("changedByName").containsExactly("Rita Rack", "login-8", "Rita Rack");
        verify(userRepository).findAllById(Set.of(7L, 8L));
    }

    /**
     * A customer sees what happened and when, and not who inside the house did it. Their lookup is
     * not merely hidden - it is never made.
     */
    @Test
    void leavesTheActorsOutForACustomer() {
        theOrder();
        when(roleService.canReadAnyOrder(caller)).thenReturn(false);
        when(historyRepository.findByOrderIdOrderByChangedAtDescIdDesc(ORDER_ID)).thenReturn(List.of(
                row(1L, null, OrderStatus.CREATED, 7L),
                row(2L, OrderStatus.CREATED, OrderStatus.ACKNOWLEDGED, 8L)));

        var history = service.findHistory(ORDER_NO, caller);

        assertThat(history).allSatisfy(entry -> {
            assertThat(entry.changedById()).isNull();
            assertThat(entry.changedByName()).isNull();
        });
        verifyNoInteractions(userRepository);
    }

    /** An automatic step has nobody to name, and asking for nobody is not a query. */
    @Test
    void namesNobodyForAnAutomaticStepAndAsksNoOne() {
        theOrder();
        when(roleService.canReadAnyOrder(caller)).thenReturn(true);
        when(historyRepository.findByOrderIdOrderByChangedAtDescIdDesc(ORDER_ID)).thenReturn(List.of(
                row(1L, OrderStatus.APPROVED, OrderStatus.IN_FULFILLMENT, null)));

        var history = service.findHistory(ORDER_NO, caller);

        assertThat(history.getFirst().changedById()).isNull();
        assertThat(history.getFirst().changedByName()).isNull();
        verify(userRepository, never()).findAllById(any());
    }

    /** A user who has since been deleted: the id stays, a name cannot be made up. */
    @Test
    void keepsTheIdOfAnActorWhoNoLongerExists() {
        theOrder();
        when(roleService.canReadAnyOrder(caller)).thenReturn(true);
        when(historyRepository.findByOrderIdOrderByChangedAtDescIdDesc(ORDER_ID)).thenReturn(List.of(
                row(1L, null, OrderStatus.CREATED, 99L)));
        when(userRepository.findAllById(Set.of(99L))).thenReturn(List.of());

        var entry = service.findHistory(ORDER_NO, caller).getFirst();

        assertThat(entry.changedById()).isEqualTo(99L);
        assertThat(entry.changedByName()).isNull();
    }
}
