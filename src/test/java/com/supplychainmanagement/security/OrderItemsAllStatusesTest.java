package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
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
import java.util.concurrent.atomic.AtomicLong;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /order-items} with {@code status=ALL} against the real chain and rows: every line, whatever it is
 * doing. Without a status the endpoint still answers PICKED (the packing work list), and a status that is none is a
 * 400 - the page's "All statuses" used to send nothing and so showed the picked lines only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class OrderItemsAllStatusesTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private OrderItem picked;
    private OrderItem reserved;
    private OrderItem waiting;

    @BeforeEach
    void scenario() {
        User customer = testData.customer();
        Storehouse storehouse = testData.storehouse();
        Order order = testData.order(customer);
        picked = testData.orderItem(order, 10, FulfillmentStatus.PICKED);
        testData.consumedReservation(picked, storehouse);
        reserved = testData.orderItem(order, 3, FulfillmentStatus.RESERVED);
        waiting = testData.orderItem(order, 2, FulfillmentStatus.WAITING);
    }

    private MockHttpServletResponse get(String query) throws Exception {
        User warehouse = testData.userWithRoles(RoleEnum.WAREHOUSE);
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(warehouse.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/order-items" + query)
                        .header("X-API-KEY", "items-all-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private static List<Long> idsOf(MockHttpServletResponse response) throws Exception {
        List<Number> ids = read(response.getContentAsString(), "$.content[*].orderItemId");
        return ids.stream().map(Number::longValue).toList();
    }

    /** Newest line first and a page wide enough that rows already in the schema cannot push ours out. */
    private static final String ALL = "?status=ALL&sort=id&order=DESC&size=100";

    @Test
    void allStatusesListsEveryLine() throws Exception {
        MockHttpServletResponse response = get(ALL);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(idsOf(response)).contains(picked.getId(), reserved.getId(), waiting.getId());
    }

    @Test
    void allIsAnyCase() throws Exception {
        assertThat(idsOf(get("?status=all&sort=id&order=DESC&size=100"))).contains(waiting.getId());
    }

    @Test
    void aStatusStillNarrowsTheList() throws Exception {
        List<Long> ids = idsOf(get("?status=WAITING&sort=id&order=DESC&size=100"));

        assertThat(ids).contains(waiting.getId()).doesNotContain(picked.getId(), reserved.getId());
    }

    @Test
    void noStatusIsStillThePickedLines() throws Exception {
        List<Long> ids = idsOf(get("?sort=id&order=DESC&size=100"));

        assertThat(ids).contains(picked.getId()).doesNotContain(reserved.getId(), waiting.getId());
    }

    @Test
    void anUnknownStatusIsA400() throws Exception {
        assertThat(get("?status=SOMETHING").getStatus()).isEqualTo(400);
    }

    @Test
    void theTotalCountsEveryLineToo() throws Exception {
        int all = read(get("?status=ALL&size=1").getContentAsString(), "$.total");
        int pickedOnly = read(get("?status=PICKED&size=1").getContentAsString(), "$.total");

        assertThat(all).isGreaterThan(pickedOnly);
    }
}
