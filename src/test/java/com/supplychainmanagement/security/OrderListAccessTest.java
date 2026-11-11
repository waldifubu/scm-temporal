package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.repository.OrderRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whose orders {@code GET /orders} lists - the list's own rule, next to the one for reading a single order
 * ({@code OrderReadAccessTest}).
 * <p>
 * A manager has no orders of their own: they work the orders of every customer. The list used to treat
 * only ADMIN as "sees all" and took everybody else for a customer, so a manager was handed the orders where
 * <em>they</em> are the customer - an empty list, with nothing to say why.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class OrderListAccessTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ObjectMapper objectMapper;

    /** The order numbers the answer lists, newest first so that this test's rows are on the page. */
    private List<Long> listedBy(User as, String path) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0" + path)
                        .header("X-API-KEY", "order-list-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        List<Long> numbers = new ArrayList<>();
        for (JsonNode row : objectMapper.readTree(response.getContentAsString()).get("content")) {
            numbers.add(row.get("orderNo").asLong());
        }
        return numbers;
    }

    private static final String NEWEST_FIRST = "?size=100&sort=id&order=DESC";

    @Test
    void aManagerSeesTheOrdersOfCustomers() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        Order ofACustomer = testData.order(testData.customer());

        assertThat(listedBy(manager, "/orders" + NEWEST_FIRST)).contains(ofACustomer.getOrderNo());
    }

    @Test
    void theWarehouseSeesTheOrdersOfCustomersToo() throws Exception {
        User warehouse = testData.userWithRoles(RoleEnum.WAREHOUSE);
        Order ofACustomer = testData.order(testData.customer());

        assertThat(listedBy(warehouse, "/orders" + NEWEST_FIRST)).contains(ofACustomer.getOrderNo());
    }

    /** The list is open to the roles that work from orders - a supplier is none of them. */
    @Test
    void aSupplierIsRefused() throws Exception {
        User supplier = testData.userWithRoles(RoleEnum.SUPPLIER);
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(supplier.getUsername(), null, List.of()));

        int status = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/orders")
                        .header("X-API-KEY", "order-list-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }

    @Test
    void theWarehouseSeesTheNewOrdersToo() throws Exception {
        User warehouse = testData.userWithRoles(RoleEnum.WAREHOUSE);
        Order ofACustomer = testData.order(testData.customer());

        assertThat(listedBy(warehouse, "/orders/new?size=100&sort=id&order=DESC")).contains(ofACustomer.getOrderNo());
    }

    @Test
    void aManagerSeesTheNewOrdersToo() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        Order ofACustomer = testData.order(testData.customer());

        assertThat(listedBy(manager, "/orders/new?size=100&sort=id&order=DESC")).contains(ofACustomer.getOrderNo());
    }

    @Test
    void anAdminSeesTheOrdersOfCustomers() throws Exception {
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        Order ofACustomer = testData.order(testData.customer());

        assertThat(listedBy(admin, "/orders" + NEWEST_FIRST)).contains(ofACustomer.getOrderNo());
    }

    private Order orderIn(User customer, OrderStatus status) {
        Order order = testData.order(customer);
        order.setStatus(status);
        return orderRepository.saveAndFlush(order);
    }

    /**
     * {@code status=ALL} is every order and {@code status=NOT_COMPLETED} every order but the closed ones - a
     * manager works from the second. One page of both, not one request per status. A rejected or cancelled order
     * is not completed, so it is in the second.
     */
    @Test
    void allListsEveryOrderAndNotCompletedLeavesOutTheCompletedOnes() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        User customer = testData.customer();
        Order created = orderIn(customer, OrderStatus.CREATED);
        Order inFulfillment = orderIn(customer, OrderStatus.IN_FULFILLMENT);
        Order rejected = orderIn(customer, OrderStatus.REJECTED);
        Order completed = orderIn(customer, OrderStatus.COMPLETED);

        assertThat(listedBy(manager, "/orders" + NEWEST_FIRST + "&status=ALL")).contains(
                created.getOrderNo(), inFulfillment.getOrderNo(), rejected.getOrderNo(), completed.getOrderNo());
        assertThat(listedBy(manager, "/orders" + NEWEST_FIRST + "&status=NOT_COMPLETED"))
                .contains(created.getOrderNo(), inFulfillment.getOrderNo(), rejected.getOrderNo())
                .doesNotContain(completed.getOrderNo());
        // The words are not case sensitive, like the statuses; a single status is still just that one.
        assertThat(listedBy(manager, "/orders" + NEWEST_FIRST + "&status=not_completed")).doesNotContain(completed.getOrderNo());
        assertThat(listedBy(manager, "/orders" + NEWEST_FIRST + "&status=COMPLETED"))
                .contains(completed.getOrderNo())
                .doesNotContain(created.getOrderNo(), inFulfillment.getOrderNo());
    }

    /** The groups obey the same rule on whose orders: a customer gets their own, whatever the word. */
    @Test
    void aCustomersGroupsHoldOnlyTheirOwnOrders() throws Exception {
        User customer = testData.userWithRoles(RoleEnum.CUSTOMER);
        Order own = orderIn(customer, OrderStatus.REVIEW);
        Order ownCompleted = orderIn(customer, OrderStatus.COMPLETED);
        Order foreign = orderIn(testData.customer(), OrderStatus.REVIEW);

        assertThat(listedBy(customer, "/orders" + NEWEST_FIRST + "&status=ALL"))
                .contains(own.getOrderNo(), ownCompleted.getOrderNo())
                .doesNotContain(foreign.getOrderNo());
        assertThat(listedBy(customer, "/orders" + NEWEST_FIRST + "&status=NOT_COMPLETED"))
                .contains(own.getOrderNo())
                .doesNotContain(ownCompleted.getOrderNo(), foreign.getOrderNo());
    }

    /** The rule the other way round: a customer still sees their own and nobody else's. */
    @Test
    void aCustomerSeesOnlyTheirOwnOrders() throws Exception {
        User customer = testData.userWithRoles(RoleEnum.CUSTOMER);
        Order own = testData.order(customer);
        Order foreign = testData.order(testData.customer());

        assertThat(listedBy(customer, "/orders" + NEWEST_FIRST))
                .contains(own.getOrderNo())
                .doesNotContain(foreign.getOrderNo());
    }
}
