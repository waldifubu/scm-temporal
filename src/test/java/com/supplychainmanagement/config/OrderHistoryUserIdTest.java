package com.supplychainmanagement.config;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderHistory;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.repository.OrderHistoryRepository;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.supplychainmanagement.support.TestData;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * That an audit row survives without a user behind it.
 * <p>
 * Not every status change has one: a scheduled sweep runs as {@code "system"}, which resolves to
 * nobody, and {@code OrderService.update(id, order)} passes no id at all.
 * {@code FulfillmentServiceImpl.userIdOf} returns {@code null} for that case on purpose - but the
 * column was created {@code NOT NULL}, so the insert failed. Inside an {@code AFTER_COMMIT} listener,
 * where that insert happens, the status change is already committed, so the caller saw a 500 for an
 * operation that had succeeded. {@link OrderHistoryUserIdMigration} widens the column on startup, and
 * {@code @SpringBootTest} runs it before this test writes.
 * <p>
 * A property of the schema, so a unit test cannot see it - every unit test in the project mocks the
 * repositories away. Needs a reachable database and rolls back.
 */
@SpringBootTest
@Import(TestData.class)
@ActiveProfiles("test")
@Transactional
class OrderHistoryUserIdTest {

    @Autowired
    private TestData testData;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderHistoryRepository orderHistoryRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void writesAnAuditRowWithoutAUser() {
        User customer = testData.customer();

        long orderNo = 997_000L;
        while (orderRepository.existsByOrderNo(orderNo)) {
            orderNo++;
        }

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setCustomer(customer);
        order.setStatus(OrderStatus.CREATED);
        order.setOrderItems(new LinkedHashSet<>());
        Order saved = orderRepository.saveAndFlush(order);

        OrderHistory entry = OrderHistory.builder()
                .userId(null)
                .order(saved)
                .previousStatus(OrderStatus.IN_FULFILLMENT)
                .newStatus(OrderStatus.APPROVED)
                .build();

        assertThatCode(() -> orderHistoryRepository.saveAndFlush(entry)).doesNotThrowAnyException();
    }
}
