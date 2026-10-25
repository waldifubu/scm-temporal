package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.users.Distributor;
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
 * {@code GET /users/distributors} - the carriers a shipment can be handed to - against the real filter
 * chain and the real rows.
 * <p>
 * What it has to get right: only <em>active carriers</em> are listed (not a disabled one, not another
 * kind of user), it is paged, and it is open to the roles that assign a carrier - LOGISTICS among them,
 * which may not read the rest of {@code /users}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class DistributorsEndpointTest {

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
                        .header("X-API-KEY", "distributors-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private Distributor distributor() {
        return (Distributor) testData.userWithRoles(RoleEnum.DISTRIBUTOR);
    }

    private JsonNode content(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        return objectMapper.readTree(response.getContentAsString()).get("content");
    }

    /** The carrier with this id in one answer, or null. */
    private JsonNode row(JsonNode content, Long id) {
        for (JsonNode row : content) {
            if (row.get("id").asLong() == id) {
                return row;
            }
        }
        return null;
    }

    @Test
    void listsAnActiveCarrierWithTheDetailsOfAUser() throws Exception {
        Distributor carrier = distributor();

        JsonNode found = row(content(get("/users/distributors?size=500", RoleEnum.LOGISTICS)), carrier.getId());

        assertThat(found).isNotNull();
        assertThat(found.get("firstName").asString()).isEqualTo("Role");
        assertThat(found.get("lastName").asString()).isEqualTo("DISTRIBUTOR");
        assertThat(found.get("email").asString()).isEqualTo(carrier.getEmail());
        assertThat(found.get("isActive").asBoolean()).isTrue();
        assertThat(found.get("roles").toString()).contains("DISTRIBUTOR");
    }

    @Test
    void leavesOutADisabledCarrierAndEveryoneWhoIsNotOne() throws Exception {
        Distributor disabled = distributor();
        disabled.setIsActive(false);
        userRepository.saveAndFlush(disabled);
        User customer = testData.customer();
        User warehouse = testData.userWithRoles(RoleEnum.WAREHOUSE);
        User logistics = testData.userWithRoles(RoleEnum.LOGISTICS);

        JsonNode all = content(get("/users/distributors?size=500", RoleEnum.ADMIN));

        assertThat(row(all, disabled.getId())).isNull();
        assertThat(row(all, customer.getId())).isNull();
        assertThat(row(all, warehouse.getId())).isNull();
        assertThat(row(all, logistics.getId())).isNull();
    }

    @Test
    void isPagedLikeEveryOtherList() throws Exception {
        distributor();
        distributor();

        JsonNode body = objectMapper.readTree(get("/users/distributors?size=1", RoleEnum.ADMIN).getContentAsString());

        assertThat(body.get("content").size()).isEqualTo(1);
        assertThat(body.get("size").asInt()).isEqualTo(1);
        assertThat(body.get("total").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void sortsByLastNameWhenAsked() throws Exception {
        distributor();
        distributor();

        JsonNode content = content(get("/users/distributors?size=500&sort=email&order=DESC", RoleEnum.MANAGER));

        List<String> emails = new java.util.ArrayList<>();
        content.forEach(row -> emails.add(row.get("email").asString()));
        assertThat(emails).hasSizeGreaterThanOrEqualTo(2).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    /** The planning side and the people above it - and nobody else. */
    @Test
    void opensTheListToAdminManagerAndLogisticsOnly() throws Exception {
        assertThat(get("/users/distributors", RoleEnum.ADMIN).getStatus()).isEqualTo(200);
        assertThat(get("/users/distributors", RoleEnum.MANAGER).getStatus()).isEqualTo(200);
        assertThat(get("/users/distributors", RoleEnum.LOGISTICS).getStatus()).isEqualTo(200);

        assertThat(get("/users/distributors", RoleEnum.WAREHOUSE).getStatus()).isEqualTo(403);
        assertThat(get("/users/distributors", RoleEnum.DISTRIBUTOR).getStatus()).isEqualTo(403);
        assertThat(get("/users/distributors", RoleEnum.CUSTOMER).getStatus()).isEqualTo(403);
    }

    /** The literal segment has to win over {@code GET /users/{id}}, which would answer 400 for "distributors". */
    @Test
    void isNotMistakenForAUserId() throws Exception {
        assertThat(get("/users/distributors", RoleEnum.LOGISTICS).getStatus()).isEqualTo(200);
    }

    /** What stays closed: LOGISTICS may read the carriers, not the user list. */
    @Test
    void doesNotOpenTheRestOfTheUserListToLogistics() throws Exception {
        assertThat(get("/users", RoleEnum.LOGISTICS).getStatus()).isEqualTo(403);
    }
}
