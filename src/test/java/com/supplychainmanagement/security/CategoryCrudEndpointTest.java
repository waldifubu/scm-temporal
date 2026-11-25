package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.ProductCategory;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Creating, changing and deleting categories against the real filter chain and real rows. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class CategoryCrudEndpointTest {

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

    private MockHttpServletResponse call(User user, HttpMethod method, String path, String body) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
        var request = MockMvcRequestBuilders.request(method, "/api/1.0" + path)
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static String body(String name, String description) {
        return "{\"name\":\"" + name + "\",\"description\":\"" + description + "\"}";
    }

    private static String unique() {
        return "Category " + UUID.randomUUID();
    }

    private ProductCategory category(String name) {
        ProductCategory category = new ProductCategory();
        category.setName(name);
        entityManager.persist(category);
        entityManager.flush();
        return category;
    }

    @Test
    void createsChangesAndDeletesACategory() throws Exception {
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        String name = unique();

        MockHttpServletResponse created = call(admin, HttpMethod.POST, "/categories", body("  " + name + " ", " "));

        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        long id = objectMapper.readTree(created.getContentAsString()).get("id").asLong();
        ProductCategory stored = entityManager.find(ProductCategory.class, id);
        assertThat(stored.getName()).isEqualTo(name);
        assertThat(stored.getDescription()).isNull();

        // Its own name is not "taken" when only the description changes.
        MockHttpServletResponse changed = call(admin, HttpMethod.PUT, "/categories/" + id, body(name, "doors"));

        assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);
        assertThat(entityManager.find(ProductCategory.class, id).getDescription()).isEqualTo("doors");

        assertThat(call(admin, HttpMethod.DELETE, "/categories/" + id, null).getStatus()).isEqualTo(204);
        assertThat(entityManager.find(ProductCategory.class, id)).isNull();
        assertThat(call(admin, HttpMethod.DELETE, "/categories/" + id, null).getStatus()).isEqualTo(404);
        assertThat(call(admin, HttpMethod.PUT, "/categories/" + id, body(name, "x")).getStatus()).isEqualTo(404);
    }

    @Test
    void refusesANameThatIsTakenWith409() throws Exception {
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        String taken = category(unique()).getName();
        ProductCategory other = category(unique());

        assertThat(call(admin, HttpMethod.POST, "/categories", body(taken, "")).getStatus()).isEqualTo(409);
        assertThat(call(admin, HttpMethod.POST, "/categories", body(taken.toUpperCase(), "")).getStatus())
                .isEqualTo(409);
        assertThat(call(admin, HttpMethod.PUT, "/categories/" + other.getId(), body(taken, "")).getStatus())
                .isEqualTo(409);
    }

    @Test
    void refusesABlankOrTooLongValueWith400() throws Exception {
        User admin = testData.userWithRoles(RoleEnum.ADMIN);

        assertThat(call(admin, HttpMethod.POST, "/categories", body(" ", "")).getStatus()).isEqualTo(400);
        assertThat(call(admin, HttpMethod.POST, "/categories", body("n".repeat(256), "")).getStatus())
                .isEqualTo(400);
        assertThat(call(admin, HttpMethod.POST, "/categories", body(unique(), "d".repeat(1001))).getStatus())
                .isEqualTo(400);
    }

    @Test
    void keepsACategoryAProductIsFiledUnder() throws Exception {
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        ProductCategory category = category(unique());
        Product product = testData.product();
        product.setCategories(new LinkedHashSet<>(List.of(category)));
        entityManager.flush();
        entityManager.clear();

        MockHttpServletResponse response = call(admin, HttpMethod.DELETE, "/categories/" + category.getId(), null);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("used by 1 product");
        assertThat(entityManager.find(ProductCategory.class, category.getId())).isNotNull();
    }

    @Test
    void filesANewProductUnderTheCategoriesItNames() throws Exception {
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        ProductCategory first = category(unique());
        ProductCategory second = category(unique());
        long articleNo = System.nanoTime();

        MockHttpServletResponse response = call(admin, HttpMethod.POST, "/products", "{\"articleNo\":" + articleNo
                + ",\"name\":\"Table\",\"unitPrice\":1,\"weight\":1,\"categoryIds\":[" + first.getId() + ","
                + second.getId() + "]}");

        assertThat(response.getStatus()).as(response.getContentAsString()).isLessThan(300);
        entityManager.flush();
        entityManager.clear();
        assertThat(entityManager.createQuery(
                        "select c.id from Product p join p.categories c where p.articleNo = :articleNo", Long.class)
                .setParameter("articleNo", articleNo)
                .getResultList())
                .containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    void changesAProductsNumberStatusAndCategories() throws Exception {
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        Product product = testData.product();
        product.setCategories(new LinkedHashSet<>(List.of(category(unique()))));
        entityManager.flush();
        long id = product.getId();
        BigDecimal weight = product.getWeight();
        ProductCategory next = category(unique());
        long articleNo = System.nanoTime();

        MockHttpServletResponse response = call(admin, HttpMethod.PUT, "/products/" + product.getArticleNo(),
                "{\"articleNo\":" + articleNo + ",\"name\":\"Renamed\",\"description\":\"new\",\"unitPrice\":1,"
                        + "\"weight\":999,\"active\":false,\"categoryIds\":[" + next.getId() + "]}");

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        entityManager.flush();
        entityManager.clear();
        Product changed = entityManager.find(Product.class, id);
        assertThat(changed.getArticleNo()).isEqualTo(articleNo);
        assertThat(changed.getName()).isEqualTo("Renamed");
        assertThat(changed.getDescription()).isEqualTo("new");
        assertThat(changed.isActive()).isFalse();
        // Sent, and ignored.
        assertThat(changed.getWeight()).isEqualByComparingTo(weight);
        assertThat(changed.getCategories()).extracting(ProductCategory::getId).containsExactly(next.getId());
    }

    @Test
    void isTheAdminsAlone() throws Exception {
        ProductCategory category = category(unique());
        User manager = testData.userWithRoles(RoleEnum.MANAGER);

        assertThat(call(manager, HttpMethod.POST, "/categories", body(unique(), "")).getStatus()).isEqualTo(403);
        assertThat(call(manager, HttpMethod.PUT, "/categories/" + category.getId(), body(unique(), "")).getStatus())
                .isEqualTo(403);
        assertThat(call(manager, HttpMethod.DELETE, "/categories/" + category.getId(), null).getStatus())
                .isEqualTo(403);
    }
}
