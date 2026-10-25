package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.StockRepository;
import com.supplychainmanagement.repository.StorehouseRepository;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /stock/{sku}} - what one article holds, storehouse by storehouse - against the real
 * filter chain and the real rows.
 * <p>
 * The point of the test is the empty case. An article stored nowhere used to be a 404, which a table
 * that lists the stock of a product had to read as "no rows"; it is an empty list with a 200 now.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class StockBySkuEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private StockRepository stockRepository;
    @Autowired
    private StorehouseRepository storehouseRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ObjectMapper objectMapper;

    private MockHttpServletResponse get(String pathAndQuery, RoleEnum role) throws Exception {
        User user = testData.userWithRoles(role);
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0" + pathAndQuery)
                        .header("X-API-KEY", "stock-by-sku-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private Stock stock(UUID sku, Storehouse storehouse, int onHand, int reserved) {
        Stock stock = new Stock();
        stock.setSku(sku);
        stock.setStorehouse(storehouse);
        stock.setOnHand(onHand);
        stock.setReserved(reserved);
        return stockRepository.saveAndFlush(stock);
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString());
    }

    @Test
    void listsWhatTheArticleHoldsInEveryStorehouse() throws Exception {
        UUID sku = UUID.randomUUID();
        Storehouse first = testData.storehouse();
        Storehouse second = testData.storehouse();
        stock(sku, first, 10, 3);
        stock(sku, second, 4, 0);

        MockHttpServletResponse response = get("/stock/" + sku, RoleEnum.WAREHOUSE);

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode rows = json(response);
        assertThat(rows.size()).isEqualTo(2);
        for (JsonNode row : rows) {
            long id = row.get("storehouseId").asLong();
            assertThat(row.get("sku").asString()).isEqualTo(sku.toString());
            assertThat(row.get("storehouseName").asString()).isNotBlank();
            if (id == first.getId()) {
                assertThat(row.get("onHand").asInt()).isEqualTo(10);
                assertThat(row.get("reserved").asInt()).isEqualTo(3);
                assertThat(row.get("available").asInt()).isEqualTo(7);
            } else {
                assertThat(id).isEqualTo(second.getId());
                assertThat(row.get("onHand").asInt()).isEqualTo(4);
                assertThat(row.get("available").asInt()).isEqualTo(4);
            }
        }
    }

    @Test
    void answersAnEmptyListForAnArticleStoredNowhere() throws Exception {
        MockHttpServletResponse response = get("/stock/" + UUID.randomUUID(), RoleEnum.ADMIN);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(json(response).isArray()).isTrue();
        assertThat(json(response).size()).isZero();
    }

    @Test
    void answersAnEmptyListForAStorehouseThatHoldsNone() throws Exception {
        UUID sku = UUID.randomUUID();
        stock(sku, testData.storehouse(), 5, 0);
        Storehouse other = testData.storehouse();

        MockHttpServletResponse response = get("/stock/" + sku + "?storehouseId=" + other.getId(), RoleEnum.ADMIN);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(json(response).size()).isZero();
    }

    @Test
    void filtersByStorehouse() throws Exception {
        UUID sku = UUID.randomUUID();
        Storehouse wanted = testData.storehouse();
        stock(sku, wanted, 5, 1);
        stock(sku, testData.storehouse(), 9, 0);

        JsonNode rows = json(get("/stock/" + sku + "?storehouseId=" + wanted.getId(), RoleEnum.WAREHOUSE));

        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).get("storehouseId").asLong()).isEqualTo(wanted.getId());
    }

    @Test
    void keepsTheRowsInTheOrderOfTheStorehouseName() throws Exception {
        UUID sku = UUID.randomUUID();
        // Named against the order they are created in, so neither the id nor the insert order is the name order.
        String[] given = {"Zeta", "Mike", "alpha", "Bravo"};
        for (String name : given) {
            Storehouse storehouse = testData.storehouse();
            storehouse.setName(name + "-" + storehouse.getId());
            storehouseRepository.saveAndFlush(storehouse);
            stock(sku, storehouse, 1, 0);
        }

        JsonNode rows = json(get("/stock/" + sku, RoleEnum.ADMIN));

        List<String> names = new java.util.ArrayList<>();
        rows.forEach(row -> names.add(row.get("storehouseName").asString()));
        assertThat(names).hasSize(4).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
    }

    /** Only the warehouse and the admin read stock - the empty answer did not open it to anybody else. */
    @Test
    void stillRefusesRolesThatMayNotReadStock() throws Exception {
        UUID sku = UUID.randomUUID();

        assertThat(get("/stock/" + sku, RoleEnum.CUSTOMER).getStatus()).isEqualTo(403);
        assertThat(get("/stock/" + sku, RoleEnum.MANAGER).getStatus()).isEqualTo(403);
    }
}
