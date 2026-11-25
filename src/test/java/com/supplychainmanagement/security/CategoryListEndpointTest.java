package com.supplychainmanagement.security;

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

import static org.assertj.core.api.Assertions.assertThat;

/** {@code GET /categories} against the real filter chain and real rows. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class CategoryListEndpointTest {

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

    private MockHttpServletResponse get(User user, String pathAndQuery) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0" + pathAndQuery)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private String category(String prefix) {
        ProductCategory category = new ProductCategory();
        category.setName(prefix + " " + UUID.randomUUID());
        category.setDescription("for the list");
        entityManager.persist(category);
        entityManager.flush();
        return category.getName();
    }

    @Test
    void listsTheCategoriesByNameForTheAdmin() throws Exception {
        String second = category("zz-b");
        String first = category("zz-a");
        User admin = testData.userWithRoles(RoleEnum.ADMIN);

        MockHttpServletResponse response = get(admin, "/categories?size=500");

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode page = objectMapper.readTree(response.getContentAsString());
        assertThat(page.get("total").asLong()).isGreaterThanOrEqualTo(2);
        List<String> names = new ArrayList<>();
        page.get("content").forEach(row -> {
            names.add(row.get("name").asString());
            assertThat(row.has("id")).isTrue();
            assertThat(row.has("products")).isFalse();
        });
        assertThat(names).containsSubsequence(first, second);
    }

    @Test
    void isTheAdminsAlone() throws Exception {
        for (RoleEnum role : List.of(RoleEnum.MANAGER, RoleEnum.WAREHOUSE, RoleEnum.CUSTOMER)) {
            assertThat(get(testData.userWithRoles(role), "/categories").getStatus()).as(role.name()).isEqualTo(403);
        }
    }
}
