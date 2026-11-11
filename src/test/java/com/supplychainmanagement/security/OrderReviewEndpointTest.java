package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /orders/{orderNo}/review}: a manager (or admin) puts an incoming order under review, and only
 * from CREATED. Real chain and real rows, so the role rule, the status guard and the stored result are all
 * the endpoint's own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class OrderReviewEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private OrderRepository orderRepository;

    private MockHttpServletResponse review(Long orderNo, User as) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/1.0/orders/" + orderNo + "/review")
                        .header("X-API-KEY", "order-review-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private Order orderIn(OrderStatus status) {
        Order order = testData.order(testData.userWithRoles(RoleEnum.CUSTOMER));
        testData.orderItem(order, 3, FulfillmentStatus.WAITING);
        order.setStatus(status);
        return order;
    }

    private OrderStatus storedStatusOf(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }

    @Test
    void aManagerTakesACreatedOrderIntoReview() throws Exception {
        Order order = orderIn(OrderStatus.CREATED);

        MockHttpServletResponse response = review(order.getOrderNo(), testData.userWithRoles(RoleEnum.MANAGER));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("REVIEW");
        assertThat(storedStatusOf(order)).isEqualTo(OrderStatus.REVIEW);
    }

    @Test
    void anAdminMayToo() throws Exception {
        Order order = orderIn(OrderStatus.CREATED);

        assertThat(review(order.getOrderNo(), testData.userWithRoles(RoleEnum.ADMIN)).getStatus()).isEqualTo(200);
    }

    @Test
    void everyStatusButCreatedAndReviewIsAConflictAndLeavesTheOrderAlone() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        for (OrderStatus status : OrderStatus.values()) {
            if (status == OrderStatus.CREATED || status == OrderStatus.REVIEW) {
                continue;
            }
            Order order = orderIn(status);

            MockHttpServletResponse response = review(order.getOrderNo(), manager);

            assertThat(response.getStatus()).as(status.name()).isEqualTo(409);
            assertThat(storedStatusOf(order)).as(status.name()).isEqualTo(status);
        }
    }

    @Test
    void aSecondReviewOfTheSameOrderChangesNothing() throws Exception {
        Order order = orderIn(OrderStatus.CREATED);
        User manager = testData.userWithRoles(RoleEnum.MANAGER);

        assertThat(review(order.getOrderNo(), manager).getStatus()).isEqualTo(200);
        assertThat(review(order.getOrderNo(), manager).getStatus()).isEqualTo(200);
        assertThat(storedStatusOf(order)).isEqualTo(OrderStatus.REVIEW);
    }

    @Test
    void customersWarehouseAndLogisticsAreRefused() throws Exception {
        Order order = orderIn(OrderStatus.CREATED);

        for (RoleEnum role : List.of(RoleEnum.CUSTOMER, RoleEnum.WAREHOUSE, RoleEnum.LOGISTICS)) {
            assertThat(review(order.getOrderNo(), testData.userWithRoles(role)).getStatus())
                    .as(role.name()).isEqualTo(403);
        }
        assertThat(storedStatusOf(order)).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void anUnknownOrderIsA404() throws Exception {
        assertThat(review(999_999_999L, testData.userWithRoles(RoleEnum.MANAGER)).getStatus()).isEqualTo(404);
    }
}
