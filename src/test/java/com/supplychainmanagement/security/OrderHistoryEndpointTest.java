package com.supplychainmanagement.security;

import com.supplychainmanagement.dto.common.DisplayNames;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.OrderStatus;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /orders/history/{orderNo}} against the real filter chain, a real order and real history
 * rows.
 * <p>
 * Who may read it is the same question as who may read the order, and the cases for the roles that
 * once were refused for every order - WAREHOUSE and LOGISTICS - are here as well as in
 * {@code OrderReadAccessTest}: a history that is reachable where its order is not would be the worse
 * hole of the two.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class OrderHistoryEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private MockHttpServletResponse history(Long orderNo, User as) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/orders/history/" + orderNo)
                        .header("X-API-KEY", "order-history-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    /** An order with the usual first three steps, the second one by the given user. */
    private Order orderWithHistory(User customer, Long actorOfSecondStep) {
        Order order = testData.order(customer);
        testData.historyRow(order, null, OrderStatus.CREATED, customer.getId());
        testData.historyRow(order, OrderStatus.CREATED, OrderStatus.ACKNOWLEDGED, actorOfSecondStep);
        testData.historyRow(order, OrderStatus.ACKNOWLEDGED, OrderStatus.APPROVED, null);
        return order;
    }

    // ------------------------------------------------------------------ the rows

    /**
     * Newest first, with the status each step came from and went to. The rows of one test are written
     * in one transaction and share a timestamp, so the order here is the tie-break by id as much as
     * the sort by time - ascending ids would turn it around.
     */
    @Test
    void answersTheStatusChangesNewestFirst() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        Order order = orderWithHistory(testData.customer(), manager.getId());

        MockHttpServletResponse response = history(order.getOrderNo(), manager);

        assertThat(response.getStatus()).as("answered: %s", response.getContentAsString()).isEqualTo(200);
        String body = response.getContentAsString();
        List<String> newStatuses = read(body, "$[*].newStatus");
        assertThat(newStatuses).containsExactly("APPROVED", "ACKNOWLEDGED", "CREATED");
        List<String> previous = read(body, "$[:2].previousStatus");
        assertThat(previous).containsExactly("ACKNOWLEDGED", "CREATED");
        List<String> changedAt = read(body, "$[*].changedAt");
        assertThat(changedAt).hasSize(3).doesNotContainNull();
    }

    /**
     * The creation comes from nowhere - and is the last row, the list being newest first. That is
     * information, so the key stays in the JSON as a null.
     */
    @Test
    void keepsTheCreationsMissingPreviousStatusAsNull() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        Order order = orderWithHistory(testData.customer(), manager.getId());

        Map<String, Object> creation = read(history(order.getOrderNo(), manager).getContentAsString(), "$[2]");

        assertThat(creation.get("newStatus")).isEqualTo("CREATED");
        assertThat(creation).containsKey("previousStatus");
        assertThat(creation.get("previousStatus")).isNull();
    }

    /** Only this order's rows - another order's history must not leak into the answer. */
    @Test
    void answersOnlyTheRowsOfThisOrder() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        Order mine = orderWithHistory(testData.customer(), manager.getId());
        orderWithHistory(testData.customer(), manager.getId());

        List<Object> rows = read(history(mine.getOrderNo(), manager).getContentAsString(), "$[*]");

        assertThat(rows).hasSize(3);
    }

    @Test
    void answersAnOrderWithoutHistoryAsAnEmptyList() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        Order bare = testData.order(testData.customer());

        MockHttpServletResponse response = history(bare.getOrderNo(), manager);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("[]");
    }

    @Test
    void answersAnUnknownOrderWith404() throws Exception {
        assertThat(history(987_654_321L, testData.userWithRoles(RoleEnum.MANAGER)).getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ who did it

    /** Staff see who made each change, by id and by the name the rest of the API uses. */
    @Test
    void namesTheActorsForStaff() throws Exception {
        User actor = testData.userWithRoles(RoleEnum.WAREHOUSE);
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        Order order = orderWithHistory(testData.customer(), actor.getId());

        String body = history(order.getOrderNo(), manager).getContentAsString();

        // Newest first: [0] an automatic step, [1] the warehouse user, [2] the customer who placed it.
        Map<String, Object> second = read(body, "$[1]");
        assertThat(second.get("newStatus")).isEqualTo("ACKNOWLEDGED");
        assertThat(((Number) second.get("changedById")).longValue()).isEqualTo(actor.getId());
        assertThat(second.get("changedByName")).isEqualTo(DisplayNames.of(actor));
    }

    /** An automatic step has nobody to name: neither key is there, and that is what null means. */
    @Test
    void leavesTheActorOutWhereNobodyActed() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        Order order = orderWithHistory(testData.customer(), manager.getId());

        Map<String, Object> automatic = read(history(order.getOrderNo(), manager).getContentAsString(), "$[0]");

        assertThat(automatic.get("newStatus")).isEqualTo("APPROVED");
        assertThat(automatic).doesNotContainKeys("changedById", "changedByName");
    }

    /**
     * A customer reads the history of their own order - what happened and when - and does not see which
     * employee did it.
     */
    @Test
    void showsACustomerTheirOwnHistoryWithoutNamingStaff() throws Exception {
        User actor = testData.userWithRoles(RoleEnum.WAREHOUSE);
        User customer = testData.userWithRoles(RoleEnum.CUSTOMER);
        Order own = orderWithHistory(customer, actor.getId());

        MockHttpServletResponse response = history(own.getOrderNo(), customer);

        assertThat(response.getStatus()).isEqualTo(200);
        String body = response.getContentAsString();
        List<String> newStatuses = read(body, "$[*].newStatus");
        assertThat(newStatuses).containsExactly("APPROVED", "ACKNOWLEDGED", "CREATED");
        assertThat(body).doesNotContain("changedBy").doesNotContain(DisplayNames.of(actor));
    }

    // ------------------------------------------------------------------ access

    /** Somebody else's order is somebody else's history, and the refusal carries none of it. */
    @Test
    void refusesACustomerAnotherCustomersHistory() throws Exception {
        User customer = testData.userWithRoles(RoleEnum.CUSTOMER);
        Order foreign = orderWithHistory(testData.customer(), null);

        MockHttpServletResponse response = history(foreign.getOrderNo(), customer);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).doesNotContain("ACKNOWLEDGED").doesNotContain("newStatus");
    }

    /** Admin and manager read every order's history. */
    @Test
    void letsAdminAndManagerReadAnyHistory() throws Exception {
        Order foreign = orderWithHistory(testData.customer(), null);

        assertThat(history(foreign.getOrderNo(), testData.userWithRoles(RoleEnum.ADMIN)).getStatus()).isEqualTo(200);
        assertThat(history(foreign.getOrderNo(), testData.userWithRoles(RoleEnum.MANAGER)).getStatus()).isEqualTo(200);
    }

    /**
     * WAREHOUSE and LOGISTICS were refused for every order, because the service treated everybody but
     * ADMIN and MANAGER as a customer. The history asks the same question as the order and must
     * answer the same way.
     */
    @Test
    void letsWarehouseAndLogisticsReadAnyHistory() throws Exception {
        Order foreign = orderWithHistory(testData.customer(), null);

        MockHttpServletResponse warehouse = history(foreign.getOrderNo(), testData.userWithRoles(RoleEnum.WAREHOUSE));
        MockHttpServletResponse logistics = history(foreign.getOrderNo(), testData.userWithRoles(RoleEnum.LOGISTICS));

        assertThat(warehouse.getStatus()).as("warehouse: %s", warehouse.getContentAsString()).isEqualTo(200);
        assertThat(logistics.getStatus()).as("logistics: %s", logistics.getContentAsString()).isEqualTo(200);
    }

    /** No token, no history. */
    @Test
    void refusesARequestWithoutAToken() throws Exception {
        Order order = orderWithHistory(testData.customer(), null);

        int status = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/orders/history/" + order.getOrderNo())
                        .header("X-API-KEY", "order-history-" + CALLS.incrementAndGet()))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
    }
}
