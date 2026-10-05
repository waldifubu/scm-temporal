package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.event.OrderStatusChangedEvent;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.supplychainmanagement.support.TestData;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a status change written the way the controllers write it actually reaches the audit trail.
 * <p>
 * It did not, which is why this exists. Rejecting and acknowledging used to set the status on the
 * order the controller had loaded and then hand that same entity to {@code OrderServiceImpl.update},
 * which loads the order again and compares. With {@code spring.jpa.open-in-view} at its default
 * {@code true} both calls share one EntityManager for the whole request, so the second load returns
 * the very instance that was already changed - and the comparison sees no change. The status still
 * reached the database (a managed entity is flushed without any save), the event did not, and no
 * {@code OrderHistory} row was written. Both now go through
 * {@code OrderProgressService.changeStatus}, which does not compare anything it reloaded.
 * <p>
 * A unit test cannot show this: with mocked repositories the two loads are whatever the stub returns.
 * So this runs one transaction and one persistence context, exactly the controller's sequence. Like
 * {@link com.supplychainmanagement.ApplicationTests} it needs a reachable database; it rolls back, so
 * nothing it writes survives.
 */
@SpringBootTest
@Import(TestData.class)
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
class OrderStatusHistoryTest {

    @Autowired
    private TestData testData;
    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ApplicationEvents events;

    private Long orderNo;

    /** An order of its own, so the test does not depend on what is in the database. */
    @BeforeEach
    void createAnOrderToWorkOn() {
        User customer = testData.customer();

        // Over 1000 and free - validateOrderNo asks for both.
        orderNo = 999_000L;
        while (orderRepository.existsByOrderNo(orderNo)) {
            orderNo++;
        }

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setCustomer(customer);
        order.setStatus(OrderStatus.CREATED);
        // Empty rather than null: acknowledge asks the availability check about every line.
        order.setOrderItems(new LinkedHashSet<>());
        orderRepository.saveAndFlush(order);
        events.clear();
    }

    private List<OrderStatusChangedEvent> statusChanges() {
        return events.stream(OrderStatusChangedEvent.class).toList();
    }

    /**
     * The controller's sequence: load the order, hand it to the service. The change has to end up in
     * the history - that is the whole point of the event.
     */
    @Test
    void recordsARejectionTheWayTheControllerWritesIt() {
        Order order = orderService.findByOrderNo(orderNo);

        orderService.reject(order, 1L);

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REJECTED);
        assertThat(statusChanges())
                .describedAs("the status reached the order, so it has to reach the history too")
                .extracting(OrderStatusChangedEvent::newStatus)
                .containsExactly(OrderStatus.REJECTED);
    }

    /** The same for acknowledging, which confirms a delivery date and then goes through update. */
    @Test
    void recordsAnAcknowledgement() {
        Order order = orderService.findByOrderNo(orderNo);

        orderService.acknowledge(order, 1L);

        assertThat(statusChanges())
                .extracting(OrderStatusChangedEvent::newStatus)
                .containsExactly(OrderStatus.ACKNOWLEDGED);
    }

    /**
     * The counter-check, and the case that always worked: a caller handing {@code update} a status the
     * loaded order does not carry. It was passing while the two above failed, which is what pinned the
     * cause on the shared persistence context rather than on the comparison itself.
     */
    @Test
    void recordsAStatusThatTheLoadedOrderDoesNotCarry() {
        Order loaded = orderService.findByOrderNo(orderNo);

        Order incoming = new Order();
        incoming.setOrderNo(orderNo);
        incoming.setCustomer(loaded.getCustomer());
        incoming.setStatus(OrderStatus.REJECTED);

        orderService.update(loaded.getId(), incoming, 1L);

        assertThat(statusChanges())
                .extracting(OrderStatusChangedEvent::newStatus)
                .containsExactly(OrderStatus.REJECTED);
    }
}
