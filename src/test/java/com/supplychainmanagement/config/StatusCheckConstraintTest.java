package com.supplychainmanagement.config;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderHistory;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.RequestStatus;
import com.supplychainmanagement.repository.ComponentRepository;
import com.supplychainmanagement.repository.OrderHistoryRepository;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.RequestComponentRepository;
import com.supplychainmanagement.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import com.supplychainmanagement.support.TestData;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * That the database really takes every status value - {@link OrderStatus} on the order and in its
 * history, {@link FulfillmentStatus} on the order line, {@link RequestStatus} on a component
 * request.
 * <p>
 * Hibernate writes a {@code CHECK} listing the values it knew when the table was created, and
 * {@code ddl-auto=update} never widens it again - a value added to the enum later is rejected at
 * insert. {@link StatusCheckConstraintMigration} rewrites those checks on startup;
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
@Import(TestData.class)
@ActiveProfiles("test")
@Transactional
@ExtendWith(org.springframework.test.context.junit.jupiter.SpringExtension.class)
class StatusCheckConstraintTest {

    @Autowired
    private TestData testData;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderHistoryRepository orderHistoryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private RequestComponentRepository requestComponentRepository;
    @Autowired
    private ComponentRepository componentRepository;

    private Order orderInStatus(OrderStatus status) {
        User customer = testData.customer();

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

    /**
     * The same for the order line. FulfillmentStatus.CANCELLED was the value that ran into the check
     * here, the way PARTIALLY_DELIVERED did on the order.
     */
    @ParameterizedTest
    @EnumSource(FulfillmentStatus.class)
    void takesEveryFulfillmentStatus(FulfillmentStatus status) {
        Order order = orderRepository.saveAndFlush(orderInStatus(OrderStatus.CREATED));

        OrderItem line = new OrderItem();
        line.setOrder(order);
        line.setProduct(testData.product());
        line.setQuantity(1);
        line.setFulfillmentStatus(status);

        assertThatCode(() -> orderItemRepository.saveAndFlush(line)).doesNotThrowAnyException();
    }

    /**
     * The same for a component request. IN_STOCK replaced STORAGE and ASSEMBLED was dropped, so the
     * clause Hibernate once wrote lists two values that no longer exist and misses the one that does.
     */
    @ParameterizedTest
    @EnumSource(RequestStatus.class)
    void takesEveryRequestStatus(RequestStatus status) {
        RequestComponent request = new RequestComponent();
        request.setComponent(testData.component());
        request.setSupplier(testData.supplier());
        request.setQty(1L);
        request.setRequestStatus(status);

        assertThatCode(() -> requestComponentRepository.saveAndFlush(request)).doesNotThrowAnyException();
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
