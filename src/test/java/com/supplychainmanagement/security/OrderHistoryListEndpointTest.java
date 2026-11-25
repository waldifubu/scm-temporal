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
 * {@code GET /orders/history}: the status changes of all orders as one paged list, against the real
 * filter chain and real rows. The list is not scoped to the caller's own orders, so who may call it is
 * the point of half of these cases.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class OrderHistoryListEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private MockHttpServletResponse get(User as, String query) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/orders/history" + query)
                        .header("X-API-KEY", "order-history-list-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private Order orderWithHistory(User customer, Long actor) {
        Order order = testData.order(customer);
        testData.historyRow(order, null, OrderStatus.CREATED, customer.getId());
        testData.historyRow(order, OrderStatus.CREATED, OrderStatus.ACKNOWLEDGED, actor);
        testData.historyRow(order, OrderStatus.ACKNOWLEDGED, OrderStatus.REVIEW, null);
        return order;
    }

    @Test
    void listsEveryStepWithItsOrderCustomerAndActor() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        User customer = testData.customer();
        Order order = orderWithHistory(customer, manager.getId());

        MockHttpServletResponse response = get(manager, "?latestOnly=false&orderNo=" + order.getOrderNo());

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        String body = response.getContentAsString();
        assertThat(((Number) read(body, "$.total")).longValue()).isEqualTo(3);
        List<String> newStatuses = read(body, "$.content[*].newStatus");
        assertThat(newStatuses).containsExactly("CREATED", "ACKNOWLEDGED", "REVIEW");
        Map<String, Object> second = read(body, "$.content[1]");
        assertThat(((Number) second.get("orderNo")).longValue()).isEqualTo(order.getOrderNo());
        assertThat(((Number) second.get("customerId")).longValue()).isEqualTo(customer.getId());
        assertThat(second.get("customerName")).isEqualTo(DisplayNames.of(customer));
        assertThat(second.get("changedByName")).isEqualTo(DisplayNames.of(manager));
        // An automatic step has no user - the id stays, as a null, and the name says it was the system.
        Map<String, Object> automatic = read(body, "$.content[2]");
        assertThat(automatic).containsKeys("changedById", "changedByName");
        assertThat(automatic.get("changedById")).isNull();
        assertThat(automatic.get("changedByName")).isEqualTo("system");
    }

    /** The default: one row per order, its newest step - and a status filter then means "is in it now". */
    @Test
    void listsOnlyTheLatestStepOfEachOrderByDefault() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        Order order = orderWithHistory(testData.customer(), manager.getId());

        String body = get(manager, "?orderNo=" + order.getOrderNo()).getContentAsString();
        assertThat(((Number) read(body, "$.total")).longValue()).isEqualTo(1);
        List<String> latest = read(body, "$.content[*].newStatus");
        assertThat(latest).containsExactly("REVIEW");

        // CREATED was a step once, but the order is not in it now.
        String created = get(manager, "?orderNo=" + order.getOrderNo() + "&status=CREATED").getContentAsString();
        assertThat(((Number) read(created, "$.total")).longValue()).isZero();
        String all = get(manager, "?latestOnly=false&orderNo=" + order.getOrderNo() + "&status=CREATED")
                .getContentAsString();
        assertThat(((Number) read(all, "$.total")).longValue()).isEqualTo(1);
    }

    @Test
    void narrowsToOneOrderAndToTheStatusWentTo() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        Order mine = orderWithHistory(testData.customer(), manager.getId());
        Order other = orderWithHistory(testData.customer(), manager.getId());

        String body = get(manager, "?latestOnly=false&orderNo=" + mine.getOrderNo()).getContentAsString();
        List<Number> orderNos = read(body, "$.content[*].orderNo");
        assertThat(orderNos).hasSize(3).allMatch(n -> n.longValue() == mine.getOrderNo());

        String byStatus = get(manager, "?status=review&size=100").getContentAsString();
        List<Number> reviewed = read(byStatus, "$.content[?(@.newStatus=='REVIEW')].orderNo");
        assertThat(reviewed.stream().map(Number::longValue)).contains(mine.getOrderNo(), other.getOrderNo());
        List<String> others = read(byStatus, "$.content[?(@.newStatus!='REVIEW')].newStatus");
        assertThat(others).isEmpty();
    }

    /** No sort: grouped by order number, and inside an order the steps in the order they happened. */
    @Test
    void groupsByOrderAndThenByTimeWhenNoSortIsAsked() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        Order a = orderWithHistory(testData.customer(), manager.getId());
        Order b = orderWithHistory(testData.customer(), manager.getId());

        String body = get(manager, "?latestOnly=false&size=200").getContentAsString();

        List<Number> numbers = read(body, "$.content[*].orderNo");
        List<Long> orderNos = numbers.stream().map(Number::longValue).toList();
        assertThat(orderNos).isSorted();
        // An order's rows are one block, not scattered.
        assertThat(orderNos.indexOf(a.getOrderNo()) + 2).isEqualTo(orderNos.lastIndexOf(a.getOrderNo()));
        assertThat(orderNos.indexOf(b.getOrderNo()) + 2).isEqualTo(orderNos.lastIndexOf(b.getOrderNo()));
        List<String> mine = read(get(manager, "?latestOnly=false&orderNo=" + a.getOrderNo()).getContentAsString(),
                "$.content[*].newStatus");
        assertThat(mine).containsExactly("CREATED", "ACKNOWLEDGED", "REVIEW");

        List<Number> descending = read(get(manager, "?latestOnly=false&order=DESC&size=200").getContentAsString(),
                "$.content[*].orderNo");
        assertThat(descending.stream().map(Number::longValue).toList()).isSortedAccordingTo(
                java.util.Comparator.<Long>reverseOrder());
    }

    @Test
    void sortsByTheOrderNumberThroughTheRowsOrder() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        Order a = orderWithHistory(testData.customer(), manager.getId());
        Order b = orderWithHistory(testData.customer(), manager.getId());

        for (String direction : List.of("ASC", "DESC")) {
            MockHttpServletResponse response = get(manager, "?sort=order.orderNo&order=" + direction + "&size=200");
            assertThat(response.getStatus()).as(direction).isEqualTo(200);
            List<Number> numbers = read(response.getContentAsString(), "$.content[*].orderNo");
            List<Long> longs = numbers.stream().map(Number::longValue).toList();
            assertThat(longs).as(direction).isSortedAccordingTo(
                    "ASC".equals(direction) ? java.util.Comparator.<Long>naturalOrder()
                            : java.util.Comparator.<Long>reverseOrder());
            assertThat(longs).contains(a.getOrderNo(), b.getOrderNo());
        }
    }

    @Test
    void refusesASortThatIsNotOnTheListWith400() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        assertThat(get(manager, "?sort=password").getStatus()).isEqualTo(400);
        assertThat(get(manager, "?status=NOPE").getStatus()).isEqualTo(400);
    }

    @Test
    void everySortableColumnWorksBothWays() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        orderWithHistory(testData.customer(), manager.getId());
        for (String column : List.of("id", "changedAt", "previousStatus", "newStatus", "userId", "order.orderNo")) {
            for (String direction : List.of("ASC", "DESC")) {
                assertThat(get(manager, "?sort=" + column + "&order=" + direction).getStatus())
                        .as(column + " " + direction).isEqualTo(200);
            }
        }
    }

    @Test
    void staffMayReadItAndACustomerMayNot() throws Exception {
        orderWithHistory(testData.customer(), null);
        for (RoleEnum role : List.of(RoleEnum.ADMIN, RoleEnum.MANAGER, RoleEnum.WAREHOUSE, RoleEnum.LOGISTICS)) {
            assertThat(get(testData.userWithRoles(role), "").getStatus()).as(role.name()).isEqualTo(200);
        }
        for (RoleEnum role : List.of(RoleEnum.CUSTOMER, RoleEnum.SUPPLIER, RoleEnum.DISTRIBUTOR)) {
            assertThat(get(testData.userWithRoles(role), "").getStatus()).as(role.name()).isEqualTo(403);
        }
    }

    @Test
    void refusesARequestWithoutAToken() throws Exception {
        int status = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/orders/history")
                        .header("X-API-KEY", "order-history-list-" + CALLS.incrementAndGet()))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }
}
