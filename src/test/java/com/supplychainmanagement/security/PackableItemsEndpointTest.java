package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
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
 * {@code GET /order-items/packable} against the real filter chain and real rows: the lines that have
 * been picked and still have room, with what is packed and what remains.
 * <p>
 * The scenario is one order with a line in every state that matters - picked, half packed, fully
 * packed, still reserved, waiting - and a second order, so the filter and the "only these two
 * statuses" rule have something to leave out. Everything is created in the test's transaction and
 * rolls back; the assertions name the lines made here, never the schema's other rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class PackableItemsEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Order order;
    private Order otherOrder;
    private Storehouse storehouse;
    private OrderItem picked;
    private OrderItem halfPacked;
    private OrderItem fullyPacked;
    private OrderItem reserved;
    private OrderItem waiting;
    private OrderItem pickedWithoutReservation;
    private Reservation pickedReservation;

    @BeforeEach
    void scenario() {
        User customer = testData.customer();
        storehouse = testData.storehouse();
        order = testData.order(customer);
        otherOrder = testData.order(customer);

        picked = testData.orderItem(order, 10, FulfillmentStatus.PICKED);
        pickedReservation = testData.consumedReservation(picked, storehouse);

        halfPacked = testData.orderItem(order, 10, FulfillmentStatus.PACKING);
        testData.consumedReservation(halfPacked, storehouse);
        // 3 + 1: two package items of one line - the packed quantity is their sum.
        testData.packedQuantity(halfPacked, 3);
        testData.packedQuantity(halfPacked, 1);

        fullyPacked = testData.orderItem(order, 5, FulfillmentStatus.PACKED);
        testData.consumedReservation(fullyPacked, storehouse);
        testData.packedQuantity(fullyPacked, 5);

        reserved = testData.orderItem(order, 3, FulfillmentStatus.RESERVED);
        waiting = testData.orderItem(order, 2, FulfillmentStatus.WAITING);

        // A pick from before the reservation was linked to its line leaves the left join empty.
        pickedWithoutReservation = testData.orderItem(otherOrder, 6, FulfillmentStatus.PICKED);
    }

    private MockHttpServletResponse get(String query, User as) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/order-items/packable" + query)
                        .header("X-API-KEY", "packable-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse asWarehouse(String query) throws Exception {
        return get(query, testData.userWithRoles(RoleEnum.WAREHOUSE));
    }

    private static List<Integer> idsOf(String body) {
        List<Number> ids = read(body, "$.content[*].orderItemId");
        return ids.stream().map(Number::intValue).toList();
    }

    private static Integer id(OrderItem item) {
        return item.getId().intValue();
    }

    // ------------------------------------------------------------------ what is listed

    /** The two statuses with room left, nothing else - of this order, which is the usual call. */
    @Test
    void listsThePickedAndThePackingLinesOfAnOrder() throws Exception {
        MockHttpServletResponse response = asWarehouse("?orderNo=" + order.getOrderNo());

        assertThat(response.getStatus()).as("answered: %.300s", response.getContentAsString()).isEqualTo(200);
        assertThat(idsOf(response.getContentAsString())).containsExactlyInAnyOrder(id(picked), id(halfPacked));
        assertThat(((Number) read(response.getContentAsString(), "$.total")).intValue()).isEqualTo(2);
    }

    @Test
    void leavesOutLinesThatArePackedStillReservedOrWaiting() throws Exception {
        String body = asWarehouse("?orderNo=" + order.getOrderNo()).getContentAsString();

        assertThat(idsOf(body)).doesNotContain(id(fullyPacked), id(reserved), id(waiting));
    }

    /** The point of the view: how much is packed and how much a PackItem may still name. */
    @Test
    void saysHowMuchIsPackedAndHowMuchRemains() throws Exception {
        String body = asWarehouse("?orderNo=" + order.getOrderNo() + "&sort=id&order=ASC").getContentAsString();

        // Ordered by id: the untouched line first, then the half packed one.
        assertThat(((Number) read(body, "$.content[0].quantity")).intValue()).isEqualTo(10);
        assertThat(((Number) read(body, "$.content[0].packed")).intValue()).isZero();
        assertThat(((Number) read(body, "$.content[0].remaining")).intValue()).isEqualTo(10);
        assertThat((String) read(body, "$.content[0].fulfillmentStatus")).isEqualTo("PICKED");

        assertThat(((Number) read(body, "$.content[1].quantity")).intValue()).isEqualTo(10);
        assertThat(((Number) read(body, "$.content[1].packed")).intValue()).as("3 + 1").isEqualTo(4);
        assertThat(((Number) read(body, "$.content[1].remaining")).intValue()).isEqualTo(6);
        assertThat((String) read(body, "$.content[1].fulfillmentStatus")).isEqualTo("PACKING");
    }

    /** The picked goods are the consumed reservation: its id, where it came from, and when. */
    @Test
    void namesTheConsumedReservationBehindThePick() throws Exception {
        String body = asWarehouse("?orderNo=" + order.getOrderNo() + "&sort=id&order=ASC").getContentAsString();

        assertThat(((Number) read(body, "$.content[0].reservationId")).longValue()).isEqualTo(pickedReservation.getId());
        assertThat(((Number) read(body, "$.content[0].storehouseId")).longValue()).isEqualTo(storehouse.getId());
        assertThat((String) read(body, "$.content[0].storehouseName")).isEqualTo(storehouse.getName());
        assertThat((String) read(body, "$.content[0].pickedAt")).isNotBlank();
        assertThat(((Number) read(body, "$.content[0].orderNo")).longValue()).isEqualTo(order.getOrderNo());
        assertThat(((Number) read(body, "$.content[0].articleNo")).longValue())
                .isEqualTo(picked.getProduct().getArticleNo());
    }

    /** A line without a reservation is still a picked line: the left join must not drop it. */
    @Test
    void keepsAPickedLineThatHasNoReservation() throws Exception {
        String body = asWarehouse("?orderNo=" + otherOrder.getOrderNo()).getContentAsString();

        assertThat(idsOf(body)).containsExactly(id(pickedWithoutReservation));
        assertThat((Object) read(body, "$.content[0].reservationId")).isNull();
        assertThat((Object) read(body, "$.content[0].storehouseId")).isNull();
        assertThat(((Number) read(body, "$.total")).intValue()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the filter

    @Test
    void narrowsToTheOrderAskedFor() throws Exception {
        String body = asWarehouse("?orderNo=" + otherOrder.getOrderNo()).getContentAsString();

        assertThat(idsOf(body)).doesNotContain(id(picked), id(halfPacked));
    }

    @Test
    void listsEveryOrdersLinesWithoutTheFilter() throws Exception {
        String body = asWarehouse("?size=200").getContentAsString();

        assertThat(idsOf(body)).contains(id(picked), id(halfPacked), id(pickedWithoutReservation))
                .doesNotContain(id(fullyPacked), id(reserved), id(waiting));
    }

    @Test
    void answersAnOrderNothingIsPickedOfWithAnEmptyList() throws Exception {
        MockHttpServletResponse response = asWarehouse("?orderNo=987654321");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(idsOf(response.getContentAsString())).isEmpty();
        assertThat(((Number) read(response.getContentAsString(), "$.total")).intValue()).isZero();
    }

    // ------------------------------------------------------------------ order and paging

    @Test
    void sortsByTheLinesOwnColumnInEitherDirection() throws Exception {
        String ascending = asWarehouse("?orderNo=" + order.getOrderNo() + "&sort=id&order=ASC").getContentAsString();
        String descending = asWarehouse("?orderNo=" + order.getOrderNo() + "&sort=id&order=DESC").getContentAsString();

        assertThat(idsOf(ascending)).containsExactly(id(picked), id(halfPacked));
        assertThat(idsOf(descending)).containsExactly(id(halfPacked), id(picked));
    }

    /** Two lines of the same size: the id decides, so the order is the same on every call. */
    @Test
    void ordersLinesThatTieByTheirId() throws Exception {
        String body = asWarehouse("?orderNo=" + order.getOrderNo() + "&sort=quantity").getContentAsString();

        assertThat(idsOf(body)).containsExactly(id(picked), id(halfPacked));
    }

    /** The count query has no left joins, the page query has - they must still agree on the total. */
    @Test
    void pagesAndCountsTheSameLines() throws Exception {
        String first = asWarehouse("?orderNo=" + order.getOrderNo() + "&size=1&page=0&sort=id").getContentAsString();
        String second = asWarehouse("?orderNo=" + order.getOrderNo() + "&size=1&page=1&sort=id").getContentAsString();

        assertThat(idsOf(first)).containsExactly(id(picked));
        assertThat(idsOf(second)).containsExactly(id(halfPacked));
        assertThat(((Number) read(first, "$.total")).intValue()).isEqualTo(2);
        assertThat(((Number) read(second, "$.total")).intValue()).isEqualTo(2);
    }

    @Test
    void refusesToSortByAJoinedColumnWith400() throws Exception {
        MockHttpServletResponse response = asWarehouse("?sort=productName");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat((String) read(response.getContentAsString(), "$.message")).contains("productName");
    }

    // ------------------------------------------------------------------ who may read it

    /** The warehouse packs, the manager watches, ADMIN may do both. */
    @Test
    void letsAdminManagerAndWarehouseRead() throws Exception {
        for (RoleEnum role : List.of(RoleEnum.ADMIN, RoleEnum.MANAGER, RoleEnum.WAREHOUSE)) {
            MockHttpServletResponse response = get("?orderNo=" + order.getOrderNo(), testData.userWithRoles(role));

            assertThat(response.getStatus()).as("%s: %.200s", role, response.getContentAsString()).isEqualTo(200);
            assertThat(idsOf(response.getContentAsString())).as(role.name()).hasSize(2);
        }
    }

    /** Everybody else is none of its business - the list names other customers' orders. */
    @Test
    void refusesEveryOtherRole() throws Exception {
        for (RoleEnum role : List.of(RoleEnum.CUSTOMER, RoleEnum.LOGISTICS, RoleEnum.SUPPLIER, RoleEnum.DISTRIBUTOR)) {
            int status = get("", testData.userWithRoles(role)).getStatus();

            assertThat(status).as(role.name()).isEqualTo(403);
        }
    }

    @Test
    void refusesARequestWithoutAToken() throws Exception {
        int status = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/order-items/packable")
                        .header("X-API-KEY", "packable-" + CALLS.incrementAndGet()))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
    }

    /** What this is for: the numbers it answers are the ones POST /packing accepts. */
    @Test
    void answersAnIdTheOtherListsCallTheSameLine() throws Exception {
        String body = asWarehouse("?orderNo=" + order.getOrderNo() + "&sort=id").getContentAsString();
        Map<String, Object> first = read(body, "$.content[0]");

        assertThat(((Number) first.get("orderItemId")).longValue()).isEqualTo(picked.getId());
        assertThat(first).containsKeys("orderItemId", "reservationId", "orderNo", "articleNo", "productName",
                "quantity", "packed", "remaining", "fulfillmentStatus", "storehouseId", "storehouseName", "pickedAt");
    }
}
