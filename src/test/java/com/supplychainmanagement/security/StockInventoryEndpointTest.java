package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.StockRepository;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /stock/inventory} - one row per article, every storehouse added up - against the real filter
 * chain and real rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class StockInventoryEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private StockRepository stockRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ObjectMapper objectMapper;

    private MockHttpServletResponse get(String pathAndQuery, RoleEnum role) throws Exception {
        User user = testData.userWithRoles(role);
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0" + pathAndQuery)
                        .header("X-API-KEY", "stock-inventory-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private JsonNode page(String pathAndQuery) throws Exception {
        MockHttpServletResponse response = get(pathAndQuery, RoleEnum.WAREHOUSE);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(response.getContentAsString());
    }

    private Stock stock(UUID sku, Storehouse storehouse, int onHand, int reserved) {
        Stock stock = new Stock();
        stock.setSku(sku);
        stock.setStorehouse(storehouse);
        stock.setOnHand(onHand);
        stock.setReserved(reserved);
        return stockRepository.saveAndFlush(stock);
    }

    private JsonNode rowOf(JsonNode page, UUID sku) {
        for (JsonNode row : page.get("content")) {
            if (row.get("sku").asString().equals(sku.toString())) {
                return row;
            }
        }
        return null;
    }

    /** The SKUs of this test in the order the page lists them. */
    private List<String> order(JsonNode page, List<UUID> mine) {
        List<String> wanted = mine.stream().map(UUID::toString).toList();
        List<String> seen = new ArrayList<>();
        page.get("content").forEach(row -> {
            if (wanted.contains(row.get("sku").asString())) {
                seen.add(row.get("sku").asString());
            }
        });
        return seen;
    }

    @Test
    void addsUpEveryStorehouseOfAnArticleAndNamesTheProduct() throws Exception {
        Product product = testData.product();
        Storehouse first = testData.storehouse();
        Storehouse second = testData.storehouse();
        stock(product.getSku(), first, 10, 2);
        Stock later = stock(product.getSku(), second, 5, 1);

        JsonNode row = rowOf(page("/stock/inventory?size=500"), product.getSku());

        assertThat(row).isNotNull();
        assertThat(row.get("onHand").asLong()).isEqualTo(15);
        assertThat(row.get("reserved").asLong()).isEqualTo(3);
        assertThat(row.get("available").asLong()).isEqualTo(12);
        assertThat(row.get("name").asString()).isEqualTo(product.getName());
        // The latest change on any of the rows, in the same format as the other lists.
        assertThat(row.get("updatedAt").asString()).startsWith(later.getUpdatedAt().toLocalDate().toString());
    }

    @Test
    void listsStockWhoseSkuNoProductCarries() throws Exception {
        UUID orphan = UUID.randomUUID();
        stock(orphan, testData.storehouse(), 4, 0);

        JsonNode row = rowOf(page("/stock/inventory?size=500"), orphan);

        assertThat(row).isNotNull();
        assertThat(row.get("onHand").asLong()).isEqualTo(4);
        assertThat(row.has("name")).isTrue();
        assertThat(row.get("name").isNull()).isTrue();
    }

    @Test
    void answersAPageWithItsFigures() throws Exception {
        stock(testData.product().getSku(), testData.storehouse(), 1, 0);
        stock(testData.product().getSku(), testData.storehouse(), 1, 0);

        JsonNode answer = page("/stock/inventory?size=1");

        assertThat(answer.get("content")).hasSize(1);
        assertThat(answer.get("page").asInt()).isZero();
        assertThat(answer.get("size").asInt()).isEqualTo(1);
        // Counted per article, not per stock row: two storehouses of one SKU are one entry.
        assertThat(answer.get("total").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void countsAnArticleOnceWhateverNumberOfStorehousesHoldsIt() throws Exception {
        long before = page("/stock/inventory?size=1").get("total").asLong();
        UUID sku = testData.product().getSku();
        stock(sku, testData.storehouse(), 1, 0);
        stock(sku, testData.storehouse(), 1, 0);
        stock(sku, testData.storehouse(), 1, 0);

        assertThat(page("/stock/inventory?size=1").get("total").asLong()).isEqualTo(before + 1);
    }

    @Test
    void sortsBySumsNotByAnyOneRow() throws Exception {
        // Three articles whose sums run against their SKU order and against the size of their biggest row.
        UUID small = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID large = UUID.randomUUID();
        Storehouse a = testData.storehouse();
        Storehouse b = testData.storehouse();
        stock(small, a, 4, 0);
        stock(middle, a, 3, 0);
        stock(middle, b, 3, 0);
        stock(large, a, 5, 0);
        stock(large, b, 5, 0);
        stock(large, testData.storehouse(), 5, 0);
        List<UUID> mine = List.of(small, middle, large);

        assertThat(order(page("/stock/inventory?size=500&sort=onHand&order=ASC"), mine))
                .containsExactly(small.toString(), middle.toString(), large.toString());
        assertThat(order(page("/stock/inventory?size=500&sort=onHand&order=DESC"), mine))
                .containsExactly(large.toString(), middle.toString(), small.toString());
    }

    @Test
    void sortsByAvailableAndByName() throws Exception {
        Product alpha = testData.product();
        alpha.setName("AAA-" + UUID.randomUUID());
        Product omega = testData.product();
        omega.setName("ZZZ-" + UUID.randomUUID());
        Storehouse house = testData.storehouse();
        // omega has more on hand but less available.
        stock(alpha.getSku(), house, 5, 0);
        stock(omega.getSku(), house, 9, 8);
        List<UUID> mine = List.of(alpha.getSku(), omega.getSku());

        assertThat(order(page("/stock/inventory?size=500&sort=available&order=DESC"), mine))
                .containsExactly(alpha.getSku().toString(), omega.getSku().toString());
        assertThat(order(page("/stock/inventory?size=500&sort=name&order=DESC"), mine))
                .containsExactly(omega.getSku().toString(), alpha.getSku().toString());
    }

    @Test
    void refusesASortItDoesNotKnowWith400() throws Exception {
        assertThat(get("/stock/inventory?sort=nonsense", RoleEnum.WAREHOUSE).getStatus()).isEqualTo(400);
        assertThat(get("/stock/inventory?size=0", RoleEnum.WAREHOUSE).getStatus()).isEqualTo(400);
    }

    @Test
    void isForTheWarehouseAndTheAdminOnly() throws Exception {
        assertThat(get("/stock/inventory", RoleEnum.ADMIN).getStatus()).isEqualTo(200);
        assertThat(get("/stock/inventory", RoleEnum.WAREHOUSE).getStatus()).isEqualTo(200);
        assertThat(get("/stock/inventory", RoleEnum.MANAGER).getStatus()).isEqualTo(403);
        assertThat(get("/stock/inventory", RoleEnum.CUSTOMER).getStatus()).isEqualTo(403);
    }
}
