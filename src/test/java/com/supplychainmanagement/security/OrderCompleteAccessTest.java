package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
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
 * {@code POST /orders/{orderNo}/complete}: the customer may close their own order, not somebody else's. The order
 * here is not delivered, so a caller who is let in meets the service's own refusal (409) - the answer that
 * tells "allowed, but not yet" from "not allowed" (403).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class OrderCompleteAccessTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private MockHttpServletResponse complete(Long orderNo, User as) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/1.0/orders/" + orderNo + "/complete")
                        .header("X-API-KEY", "order-complete-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private Order orderOf(User customer) {
        Order order = testData.order(customer);
        testData.orderItem(order, 3, FulfillmentStatus.WAITING);
        return order;
    }

    @Test
    void theCustomerOfTheOrderIsLetIn() throws Exception {
        User customer = testData.userWithRoles(RoleEnum.CUSTOMER);

        MockHttpServletResponse response = complete(orderOf(customer).getOrderNo(), customer);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
    }

    @Test
    void anotherCustomerIsRefused() throws Exception {
        Order order = orderOf(testData.userWithRoles(RoleEnum.CUSTOMER));

        assertThat(complete(order.getOrderNo(), testData.userWithRoles(RoleEnum.CUSTOMER)).getStatus()).isEqualTo(403);
    }

    @Test
    void aManagerMayCloseAnyOrderAndAWarehouseNone() throws Exception {
        Order order = orderOf(testData.userWithRoles(RoleEnum.CUSTOMER));

        assertThat(complete(order.getOrderNo(), testData.userWithRoles(RoleEnum.MANAGER)).getStatus()).isEqualTo(409);
        assertThat(complete(order.getOrderNo(), testData.userWithRoles(RoleEnum.WAREHOUSE)).getStatus()).isEqualTo(403);
    }
}
