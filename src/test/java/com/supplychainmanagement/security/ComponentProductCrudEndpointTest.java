package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.ComponentRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
 * Creating and deleting components and products through the real chain: the roles, what is stored, and that a
 * delete of something still referenced is a 409 and not the 500 of a constraint at the commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class ComponentProductCrudEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ComponentRepository componentRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private MockHttpServletResponse call(String method, User as, String path, String json) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        var request = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),
                        "/api/1.0" + path)
                .header("X-API-KEY", "crud-" + CALLS.incrementAndGet())
                .header("Authorization", "Bearer " + token);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    @Test
    void createsAComponentOfAProductAtTheUnslashedPath() throws Exception {
        Product product = testData.product();
        User manager = testData.userWithRoles(RoleEnum.MANAGER);

        MockHttpServletResponse response = call("POST", manager, "/components",
                "{\"productArticleNo\":" + product.getArticleNo() + ",\"name\":\"Schraube\",\"qty\":4}");

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        Number id = read(response.getContentAsString(), "$.id");
        assertThat(componentRepository.findById(id.longValue()).orElseThrow().getName()).isEqualTo("Schraube");
    }

    @Test
    void creatingAComponentRecomputesTheWeightOfItsProduct() throws Exception {
        Product product = testData.product();
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        String base = "{\"productArticleNo\":" + product.getArticleNo() + ",\"name\":\"Part\",\"weight\":";

        assertThat(call("POST", manager, "/components", base + "2.5}").getStatus()).isEqualTo(200);
        assertThat(call("POST", manager, "/components", base + "4}").getStatus()).isEqualTo(200);

        entityManager.flush();
        entityManager.clear();
        var expected = componentRepository.findByProductId(product.getId()).stream()
                .map(c -> c.getWeight()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getWeight())
                .isEqualByComparingTo(expected);
        assertThat(expected).isGreaterThanOrEqualTo(new java.math.BigDecimal("6.5"));
    }

    @Test
    void patchingTheWeightOfAComponentRecomputesTheWeightOfItsProduct() throws Exception {
        Product product = testData.product();
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        String base = "{\"productArticleNo\":" + product.getArticleNo() + ",\"name\":\"Part\",\"weight\":";
        var created = call("POST", manager, "/components", base + "2.5}");
        assertThat(created.getStatus()).isEqualTo(200);
        String sku = read(created.getContentAsString(), "$.sku");

        var patched = call("PATCH", manager, "/components/" + sku, "{\"weight\":9}");
        assertThat(patched.getStatus()).as(patched.getContentAsString()).isEqualTo(200);

        entityManager.flush();
        entityManager.clear();
        var expected = componentRepository.findByProductId(product.getId()).stream()
                .map(c -> c.getWeight()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getWeight())
                .isEqualByComparingTo(expected);
        assertThat(expected).isGreaterThanOrEqualTo(new java.math.BigDecimal("9"));
    }

    @Test
    void deletingAComponentRecomputesTheWeightOfItsProduct() throws Exception {
        Product product = testData.product();
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        String base = "{\"productArticleNo\":" + product.getArticleNo() + ",\"name\":\"Part\",\"weight\":";
        var first = call("POST", admin, "/components", base + "2.5}");
        var second = call("POST", admin, "/components", base + "4}");
        Number secondId = read(second.getContentAsString(), "$.id");
        Number firstId = read(first.getContentAsString(), "$.id");

        assertThat(call("DELETE", admin, "/components/" + secondId.longValue(), null).getStatus()).isEqualTo(200);

        entityManager.flush();
        entityManager.clear();
        var expected = componentRepository.findByProductId(product.getId()).stream()
                .map(c -> c.getWeight()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        assertThat(componentRepository.findByProductId(product.getId()))
                .extracting(c -> c.getId()).contains(firstId.longValue()).doesNotContain(secondId.longValue());
        assertThat(productRepository.findById(product.getId()).orElseThrow().getWeight())
                .isEqualByComparingTo(expected);
    }

    @Test
    void creatingAComponentIsForAdminAndManagerAndNeedsAProductAndAName() throws Exception {
        Product product = testData.product();
        String body = "{\"productArticleNo\":" + product.getArticleNo() + ",\"name\":\"X\"}";
        assertThat(call("POST", testData.userWithRoles(RoleEnum.ADMIN), "/components", body).getStatus()).isEqualTo(200);
        for (RoleEnum role : List.of(RoleEnum.WAREHOUSE, RoleEnum.CUSTOMER, RoleEnum.LOGISTICS)) {
            assertThat(call("POST", testData.userWithRoles(role), "/components", body).getStatus())
                    .as(role.name()).isEqualTo(403);
        }
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        assertThat(call("POST", manager, "/components", "{\"name\":\"X\"}").getStatus()).isEqualTo(400);
        assertThat(call("POST", manager, "/components",
                "{\"productArticleNo\":" + product.getArticleNo() + ",\"name\":\" \"}").getStatus()).isEqualTo(400);
    }

    @Test
    void deletesAComponentForAdminOnly() throws Exception {
        Long id = testData.component().getId();

        assertThat(call("DELETE", testData.userWithRoles(RoleEnum.MANAGER), "/components/" + id, null).getStatus())
                .isEqualTo(403);
        assertThat(call("DELETE", testData.userWithRoles(RoleEnum.ADMIN), "/components/" + id, null).getStatus())
                .isEqualTo(200);
        assertThat(componentRepository.existsById(id)).isFalse();
        assertThat(call("DELETE", testData.userWithRoles(RoleEnum.ADMIN), "/components/" + id, null).getStatus())
                .isEqualTo(404);
    }

    @Test
    void createsAndDeletesAProduct() throws Exception {
        User manager = testData.userWithRoles(RoleEnum.MANAGER);
        long articleNo = 7_000_000L + CALLS.incrementAndGet();

        MockHttpServletResponse created = call("POST", manager, "/products",
                "{\"articleNo\":" + articleNo + ",\"name\":\"Tisch\",\"unitPrice\":49.5,\"weight\":12.5,\"active\":true}");

        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(200);
        Number id = read(created.getContentAsString(), "$.id");
        assertThat(productRepository.findById(id.longValue()).orElseThrow().getName()).isEqualTo("Tisch");

        assertThat(call("DELETE", testData.userWithRoles(RoleEnum.WAREHOUSE), "/products/" + articleNo, null).getStatus())
                .isEqualTo(403);
        assertThat(call("DELETE", manager, "/products/" + articleNo, null).getStatus()).isEqualTo(200);
        assertThat(productRepository.existsById(id.longValue())).isFalse();
        // Gone is gone: the same number again is nobody's.
        assertThat(call("DELETE", manager, "/products/" + articleNo, null).getStatus()).isEqualTo(404);
    }

    /** The path names a product by its article number, for changing it as for removing it - not by the database id. */
    @Test
    void doesNotTakeTheDatabaseIdForAnArticleNumber() throws Exception {
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        Product product = testData.product();

        assertThat(call("DELETE", admin, "/products/" + product.getId(), null).getStatus()).isEqualTo(404);

        assertThat(productRepository.existsById(product.getId())).isTrue();
    }

    @Test
    void refusesToDeleteAProductAnOrderLineStillNames() throws Exception {
        Order order = testData.order(testData.customer());
        OrderItem item = testData.orderItem(order, 1, FulfillmentStatus.WAITING);
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        Long articleNo = item.getProduct().getArticleNo();
        // In production the delete is a request of its own and the order line is not in its session; here the
        // test's transaction is shared, so what the test made is let go first.
        entityManager.flush();
        entityManager.clear();

        MockHttpServletResponse response = call("DELETE", admin, "/products/" + articleNo, null);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
    }
}
