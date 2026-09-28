package com.supplychainmanagement.config;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderHistory;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.repository.OrderHistoryRepository;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * That the database really takes every {@link OrderStatus}, on the order and in its history.
 * <p>
 * Hibernate writes a {@code CHECK} listing the values it knew when the table was created, and
 * {@code ddl-auto=update} never widens it again - a value added to the enum later is rejected at
 * insert. {@link OrderStatusCheckConstraintMigration} rewrites those checks on startup;
 * {@code @SpringBootTest} runs it, so by the time this test writes, it has run.
 * <p>
 * Worth a context test rather than a unit test: this is a property of the schema, and every unit
 * test in the project mocks the repositories away. {@code PARTIALLY_DELIVERED} was the value that
 * would have failed - and failed twice over, because the audit row carries the status as well and
 * its insert happens inside an {@code AFTER_COMMIT} listener, where the error turns an operation
 * that already succeeded into a 500.
 * <p>
 * Needs a reachable database, like {@link com.supplychainmanagement.ApplicationTests}, and rolls
 * back - nothing it writes survives.
 */
@SpringBootTest
@Transactional
@ExtendWith(org.springframework.test.context.junit.jupiter.SpringExtension.class)
class OrderStatusCheckConstraintTest {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderHistoryRepository orderHistoryRepository;
    @Autowired
    private UserRepository userRepository;

    private Order orderInStatus(OrderStatus status) {
        User customer = userRepository.findAll().getFirst();

        long orderNo = 998_000L;
        while (orderRepository.existsByOrderNo(orderNo)) {
            orderNo++;
        }

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setCustomer(customer);
        order.setStatus(status);
        order.setOrderItems(new LinkedHashSet<>());
        return order;
    }

    /** Every value the enum has, written to orders.order_status. */
    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void takesEveryOrderStatus(OrderStatus status) {
        assertThatCode(() -> orderRepository.saveAndFlush(orderInStatus(status)))
                .doesNotThrowAnyException();
    }

    /** The same for both status columns of the audit row - they carry their own check each. */
    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void takesEveryOrderStatusInTheHistory(OrderStatus status) {
        Order order = orderRepository.saveAndFlush(orderInStatus(OrderStatus.CREATED));

        OrderHistory entry = OrderHistory.builder()
                .userId(null)
                .order(order)
                .previousStatus(status)
                .newStatus(status)
                .build();

        assertThatCode(() -> orderHistoryRepository.saveAndFlush(entry)).doesNotThrowAnyException();
    }
}
