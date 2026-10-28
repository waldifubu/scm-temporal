package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
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
 * {@code GET /products} as a page - {@code {content, total, page, size}} - against the real filter chain and
 * real rows. It used to answer a bare list of every product there is.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class ProductListEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EntityManager entityManager;

    private MockHttpServletResponse get(String pathAndQuery, RoleEnum role) throws Exception {
        User user = testData.userWithRoles(role);
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0" + pathAndQuery)
                        .header("X-API-KEY", "product-list-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private JsonNode page(String pathAndQuery, RoleEnum role) throws Exception {
        MockHttpServletResponse response = get(pathAndQuery, role);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(response.getContentAsString());
    }

    private List<Long> articleNos(JsonNode page, List<Product> mine) {
        List<Long> wanted = mine.stream().map(Product::getArticleNo).toList();
        List<Long> numbers = new ArrayList<>();
        page.get("content").forEach(row -> {
            if (wanted.contains(row.get("articleNo").asLong())) {
                numbers.add(row.get("articleNo").asLong());
            }
        });
        return numbers;
    }

    @Test
    void answersAPageWithItsFigures() throws Exception {
        testData.product();
        testData.product();

        JsonNode answer = page("/products?size=1", RoleEnum.ADMIN);

        assertThat(answer.get("content")).hasSize(1);
        assertThat(answer.get("page").asInt()).isZero();
        assertThat(answer.get("size").asInt()).isEqualTo(1);
        assertThat(answer.get("total").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void pagesDoNotRepeatOrSkipAProduct() throws Exception {
        List<Product> mine = List.of(testData.product(), testData.product(), testData.product(), testData.product(), testData.product());

        List<Long> seen = new ArrayList<>();
        for (int number = 0; number < 1000; number++) {
            JsonNode answer = page("/products?size=2&page=" + number + "&sort=name", RoleEnum.ADMIN);
            if (answer.get("content").isEmpty()) {
                break;
            }
            seen.addAll(articleNos(answer, mine));
        }

        // The five share one name, so the sort value ties on all of them: only the id keeps the pages apart.
        assertThat(seen).containsExactlyInAnyOrderElementsOf(mine.stream().map(Product::getArticleNo).toList());
    }

    @Test
    void sortsByTheArticleNumberBothWays() throws Exception {
        Product first = testData.product();
        Product second = testData.product();
        Product third = testData.product();
        List<Product> mine = List.of(first, second, third);

        assertThat(articleNos(page("/products?size=500&sort=articleNo&order=DESC", RoleEnum.ADMIN), mine))
                .containsExactly(third.getArticleNo(), second.getArticleNo(), first.getArticleNo());
        assertThat(articleNos(page("/products?size=500&sort=articleNo&order=ASC", RoleEnum.ADMIN), mine))
                .containsExactly(first.getArticleNo(), second.getArticleNo(), third.getArticleNo());
    }

    @Test
    void refusesASortItDoesNotKnowWith400() throws Exception {
        assertThat(get("/products?sort=nonsense", RoleEnum.ADMIN).getStatus()).isEqualTo(400);
        assertThat(get("/products?page=-1", RoleEnum.ADMIN).getStatus()).isEqualTo(400);
        assertThat(get("/products?size=0", RoleEnum.ADMIN).getStatus()).isEqualTo(400);
    }

    /** A manager's rows carry the components, a customer's do not and have no id - paging changed neither. */
    @Test
    void keepsTheViewOfEachRole() throws Exception {
        testData.component();
        // The product in the session knows no components yet; the list has to read them from the database.
        entityManager.flush();
        entityManager.clear();

        JsonNode asManager = page("/products?size=500&sort=id&order=DESC", RoleEnum.MANAGER).get("content").get(0);
        JsonNode asCustomer = page("/products?size=500&sort=id&order=DESC", RoleEnum.CUSTOMER).get("content").get(0);

        assertThat(asManager.has("id")).isTrue();
        assertThat(asManager.get("components")).isNotNull().hasSize(1);
        assertThat(asCustomer.has("id")).isFalse();
        assertThat(asCustomer.path("components")).isEmpty();
    }
}
