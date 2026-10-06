package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.users.User;
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
 * Who may read a <em>real</em> order - the half of authorization that {@code ApiAuthorizationTest}
 * cannot see.
 * <p>
 * That matrix asks only whether a role gets past {@code @PreAuthorize}, against an order number that
 * does not exist, so the answer is a 404 whatever the service would have decided. The service has a
 * second check of its own: it treats everybody who is not {@code isPrivilegedUser} as a customer and
 * compares them with the order's owner. {@code isPrivilegedUser} is ADMIN and MANAGER - so WAREHOUSE
 * and LOGISTICS, which {@code @PreAuthorize} lets through, were refused with "This order belongs to
 * another customer" for every order there is.
 * <p>
 * Needs an order that exists, which is why this is a context test with one of its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class OrderReadAccessTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private MockHttpServletResponse readOrder(Long orderNo, User as) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/orders/" + orderNo)
                        .header("X-API-KEY", "order-read-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    /** Somebody else's order, which no role under test owns. */
    private Order anotherCustomersOrder() {
        return testData.order(testData.customer());
    }

    // ------------------------------------------------------------------ the customer's own rule

    @Test
    void letsACustomerReadTheirOwnOrder() throws Exception {
        User customer = testData.userWithRoles(RoleEnum.CUSTOMER);
        Order own = testData.order(customer);

        assertThat(readOrder(own.getOrderNo(), customer).getStatus()).isEqualTo(200);
    }

    @Test
    void refusesACustomerAnotherCustomersOrder() throws Exception {
        User customer = testData.userWithRoles(RoleEnum.CUSTOMER);

        assertThat(readOrder(anotherCustomersOrder().getOrderNo(), customer).getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ everybody else who may read

    @Test
    void letsAManagerReadAnyOrder() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);

        assertThat(readOrder(anotherCustomersOrder().getOrderNo(), manager).getStatus()).isEqualTo(200);
    }

    @Test
    void letsAnAdminReadAnyOrder() throws Exception {
        User admin = testData.userWithRoles(RoleEnum.ADMIN);

        assertThat(readOrder(anotherCustomersOrder().getOrderNo(), admin).getStatus()).isEqualTo(200);
    }

    /**
     * GET /orders/{orderNo} names WAREHOUSE in its @PreAuthorize and the README lists it - the
     * warehouse picks and packs what customers ordered, none of which is its own.
     */
    @Test
    void letsTheWarehouseReadAnyOrder() throws Exception {
        User warehouse = testData.userWithRoles(RoleEnum.WAREHOUSE);

        MockHttpServletResponse response = readOrder(anotherCustomersOrder().getOrderNo(), warehouse);

        assertThat(response.getStatus()).as("answered: %s", response.getContentAsString()).isEqualTo(200);
    }

    /**
     * LOGISTICS was added to this endpoint on purpose, because a shipment response carries no due date
     * and a planner cannot judge urgency without one. It never reached it: refused for every order.
     */
    @Test
    void letsLogisticsReadAnyOrder() throws Exception {
        User logistics = testData.userWithRoles(RoleEnum.LOGISTICS);

        MockHttpServletResponse response = readOrder(anotherCustomersOrder().getOrderNo(), logistics);

        assertThat(response.getStatus()).as("answered: %s", response.getContentAsString()).isEqualTo(200);
    }

    /** An order that does not exist is still a 404 for everybody - the check order did not change. */
    @Test
    void answersAnUnknownOrderWith404() throws Exception {
        User warehouse = testData.userWithRoles(RoleEnum.WAREHOUSE);

        assertThat(readOrder(987_654_321L, warehouse).getStatus()).isEqualTo(404);
    }
}
