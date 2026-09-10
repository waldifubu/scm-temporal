package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.RoleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Who may read which order. The endpoint is open to CUSTOMER, so the ownership check is the only
 * thing between a customer and someone else's order - worth testing on its own.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceAccessTest {

    private static final Long ORDER_NO = 1042L;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleService roleService;

    @InjectMocks
    private OrderServiceImpl service;

    private org.springframework.security.core.userdetails.User principal(String username) {
        return new org.springframework.security.core.userdetails.User(username, "", List.of());
    }

    private Order orderOfCustomer(Long customerId) {
        User customer = new User();
        customer.setId(customerId);

        Order order = new Order();
        order.setId(42L);
        order.setOrderNo(ORDER_NO);
        order.setCustomer(customer);

        when(orderRepository.findByOrderNo(ORDER_NO)).thenReturn(Optional.of(order));
        return order;
    }

    private void callerIsCustomerWithId(String username, Long id) {
        User caller = new User();
        caller.setId(id);
        caller.setUsername(username);

        when(roleService.isPrivilegedUser(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(userRepository.findByUsernameOrEmail(username, username)).thenReturn(Optional.of(caller));
    }

    @Test
    void letsACustomerReadTheirOwnOrder() {
        Order order = orderOfCustomer(3L);
        callerIsCustomerWithId("kunde", 3L);

        assertThat(service.findByOrderNoForUser(ORDER_NO, principal("kunde"))).isSameAs(order);
    }

    @Test
    void refusesACustomerAnotherCustomersOrder() {
        orderOfCustomer(3L);
        callerIsCustomerWithId("fremder", 4L);

        assertThatThrownBy(() -> service.findByOrderNoForUser(ORDER_NO, principal("fremder")))
                .isInstanceOf(APIException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    /** An order without a customer belongs to nobody - and therefore not to the caller either. */
    @Test
    void refusesACustomerAnOrderWithoutACustomer() {
        Order order = orderOfCustomer(3L);
        order.setCustomer(null);
        callerIsCustomerWithId("kunde", 3L);

        assertThatThrownBy(() -> service.findByOrderNoForUser(ORDER_NO, principal("kunde")))
                .isInstanceOf(APIException.class);
    }

    /** Admin, manager and warehouse look at orders that are never "theirs" - no ownership check. */
    @Test
    void letsAPrivilegedCallerReadAnyOrder() {
        Order order = orderOfCustomer(3L);
        when(roleService.isPrivilegedUser(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        assertThat(service.findByOrderNoForUser(ORDER_NO, principal("manager"))).isSameAs(order);
    }
}
