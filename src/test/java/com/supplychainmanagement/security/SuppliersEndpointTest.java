package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.UserRepository;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /users/suppliers} - the suppliers a component request can be placed with - against the
 * real filter chain and the real rows.
 * <p>
 * Same shape as {@code DistributorsEndpointTest}; what is different is who may ask: the roles that may
 * <em>place</em> a request - ADMIN, MANAGER and WAREHOUSE. The warehouse is the one that matters: it may
 * order components but not read the rest of {@code /users}, and the carriers' role (LOGISTICS) has
 * nothing to do with suppliers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class SuppliersEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ObjectMapper objectMapper;

    private MockHttpServletResponse get(String pathAndQuery, RoleEnum role) throws Exception {
        User user = testData.userWithRoles(role);
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0" + pathAndQuery)
                        .header("X-API-KEY", "suppliers-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private Supplier supplier() {
        return (Supplier) testData.userWithRoles(RoleEnum.SUPPLIER);
    }

    private JsonNode content(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        return objectMapper.readTree(response.getContentAsString()).get("content");
    }

    private JsonNode row(JsonNode content, Long id) {
        for (JsonNode row : content) {
            if (row.get("id").asLong() == id) {
                return row;
            }
        }
        return null;
    }

    @Test
    void listsAnActiveSupplierWithTheDetailsOfAUser() throws Exception {
        Supplier supplier = supplier();

        JsonNode found = row(content(get("/users/suppliers?size=500", RoleEnum.WAREHOUSE)), supplier.getId());

        assertThat(found).isNotNull();
        assertThat(found.get("firstName").asString()).isEqualTo("Role");
        assertThat(found.get("lastName").asString()).isEqualTo("SUPPLIER");
        assertThat(found.get("email").asString()).isEqualTo(supplier.getEmail());
        assertThat(found.get("isActive").asBoolean()).isTrue();
        assertThat(found.get("roles").toString()).contains("SUPPLIER");
    }

    /** A request placed with a disabled supplier would sit in OPEN for good - nobody can answer it. */
    @Test
    void leavesOutADisabledSupplierAndEveryoneWhoIsNotOne() throws Exception {
        Supplier disabled = supplier();
        disabled.setIsActive(false);
        userRepository.saveAndFlush(disabled);
        User customer = testData.customer();
        User carrier = testData.userWithRoles(RoleEnum.DISTRIBUTOR);
        User warehouse = testData.userWithRoles(RoleEnum.WAREHOUSE);

        JsonNode all = content(get("/users/suppliers?size=500", RoleEnum.ADMIN));

        assertThat(row(all, disabled.getId())).isNull();
        assertThat(row(all, customer.getId())).isNull();
        assertThat(row(all, carrier.getId())).isNull();
        assertThat(row(all, warehouse.getId())).isNull();
    }

    @Test
    void isPagedLikeEveryOtherList() throws Exception {
        supplier();
        supplier();

        JsonNode body = objectMapper.readTree(get("/users/suppliers?size=1", RoleEnum.ADMIN).getContentAsString());

        assertThat(body.get("content").size()).isEqualTo(1);
        assertThat(body.get("size").asInt()).isEqualTo(1);
        assertThat(body.get("total").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void sortsByEmailWhenAsked() throws Exception {
        supplier();
        supplier();

        JsonNode content = content(get("/users/suppliers?size=500&sort=email&order=DESC", RoleEnum.MANAGER));

        List<String> emails = new java.util.ArrayList<>();
        content.forEach(row -> emails.add(row.get("email").asString()));
        assertThat(emails).hasSizeGreaterThanOrEqualTo(2).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    /** The roles that may place a component request - and nobody else. */
    @Test
    void opensTheListToAdminManagerAndWarehouseOnly() throws Exception {
        assertThat(get("/users/suppliers", RoleEnum.ADMIN).getStatus()).isEqualTo(200);
        assertThat(get("/users/suppliers", RoleEnum.MANAGER).getStatus()).isEqualTo(200);
        assertThat(get("/users/suppliers", RoleEnum.WAREHOUSE).getStatus()).isEqualTo(200);

        assertThat(get("/users/suppliers", RoleEnum.LOGISTICS).getStatus()).isEqualTo(403);
        assertThat(get("/users/suppliers", RoleEnum.DISTRIBUTOR).getStatus()).isEqualTo(403);
        assertThat(get("/users/suppliers", RoleEnum.SUPPLIER).getStatus()).isEqualTo(403);
        assertThat(get("/users/suppliers", RoleEnum.CUSTOMER).getStatus()).isEqualTo(403);
    }

    /** The literal segment has to win over {@code GET /users/{id}}, which would answer 400 for "suppliers". */
    @Test
    void isNotMistakenForAUserId() throws Exception {
        assertThat(get("/users/suppliers", RoleEnum.WAREHOUSE).getStatus()).isEqualTo(200);
    }

    /** What stays closed: the warehouse may read the suppliers, not the user list. */
    @Test
    void doesNotOpenTheRestOfTheUserListToTheWarehouse() throws Exception {
        assertThat(get("/users", RoleEnum.WAREHOUSE).getStatus()).isEqualTo(403);
    }
}
