package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
 * {@code GET /orders/workflow} against the real filter chain: the table the services decide by, as the JSON a client
 * builds its buttons from. The literal segment has to win over {@code GET /orders/{orderNo}} - the number parse of
 * "workflow" would be a 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class OrderWorkflowEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ObjectMapper objectMapper;

    private MockHttpServletResponse get(RoleEnum role) throws Exception {
        return get("/api/1.0/orders/workflow", role);
    }

    private MockHttpServletResponse get(String path, RoleEnum role) throws Exception {
        User user = testData.userWithRoles(role);
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .header("X-API-KEY", "order-workflow-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    @ParameterizedTest
    @EnumSource(value = RoleEnum.class, names = {"ADMIN", "MANAGER", "WAREHOUSE", "LOGISTICS", "CUSTOMER", "DISTRIBUTOR"})
    void everyRoleThatReadsOrdersMayReadTheWorkflow(RoleEnum role) throws Exception {
        assertThat(get(role).getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @EnumSource(value = RoleEnum.class, names = "SUPPLIER")
    void aRoleWithNoBusinessWithOrdersIsRefused(RoleEnum role) throws Exception {
        assertThat(get(role).getStatus()).isEqualTo(403);
    }

    @Test
    void theDiagramIsOpenToTheSameRolesAndRefusesTheOthers() throws Exception {
        for (RoleEnum role : List.of(RoleEnum.ADMIN, RoleEnum.MANAGER, RoleEnum.WAREHOUSE, RoleEnum.LOGISTICS,
                RoleEnum.CUSTOMER)) {
            assertThat(get("/api/1.0/orders/workflow/diagram", role).getStatus()).as(role.name()).isEqualTo(200);
        }
        assertThat(get("/api/1.0/orders/workflow/diagram", RoleEnum.SUPPLIER).getStatus()).isEqualTo(403);
        assertThat(get("/api/1.0/orders/workflow/diagram", RoleEnum.DISTRIBUTOR).getStatus()).isEqualTo(200);
    }

    @Test
    void theDiagramCarriesNodesWithTheirMeaningArrowsAndEnds() throws Exception {
        JsonNode body = objectMapper.readTree(
                get("/api/1.0/orders/workflow/diagram", RoleEnum.CUSTOMER).getContentAsString());

        assertThat(body.get("nodes").size()).isEqualTo(OrderStatus.values().length);
        JsonNode waitSupply = null;
        for (JsonNode node : body.get("nodes")) {
            assertThat(node.get("description").asString()).isNotBlank();
            if ("WAIT_SUPPLY".equals(node.get("id").asString())) {
                waitSupply = node;
            }
        }
        assertThat(waitSupply).isNotNull();
        assertThat(waitSupply.get("label").asString()).isEqualTo("WAIT SUPPLY");

        List<String> ends = new ArrayList<>();
        body.get("ends").forEach(node -> ends.add(node.asString()));
        assertThat(ends).containsExactly("REJECTED", "CANCELLED");

        boolean dotted = false;
        for (JsonNode edge : body.get("edges")) {
            if ("BACK".equals(edge.get("kind").asString())) {
                dotted = true;
                assertThat(edge.get("label").asString()).isNotBlank();
            }
        }
        assertThat(dotted).isTrue();
    }

    @Test
    void needsALogin() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/orders/workflow")
                        .header("X-API-KEY", "order-workflow-" + CALLS.incrementAndGet()))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void answersStatusesAndStepsAsTheWorkflowHoldsThem() throws Exception {
        MockHttpServletResponse response = get(RoleEnum.CUSTOMER);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("initialStatus").asString()).isEqualTo("CREATED");
        List<String> statuses = new ArrayList<>();
        body.get("statuses").forEach(node -> statuses.add(node.asString()));
        assertThat(statuses).containsExactlyInAnyOrderElementsOf(
                java.util.Arrays.stream(OrderStatus.values()).map(Enum::name).toList());

        JsonNode accept = null;
        JsonNode noCondition = null;
        for (JsonNode step : body.get("transitions")) {
            if ("CREATED".equals(step.get("from").asString()) && "WAIT_SUPPLY".equals(step.get("to").asString())) {
                accept = step;
            }
            if ("CREATED".equals(step.get("from").asString()) && "REVIEW".equals(step.get("to").asString())) {
                noCondition = step;
            }
        }
        assertThat(accept).isNotNull();
        assertThat(accept.get("action").asString()).isEqualTo("ACCEPT");
        assertThat(accept.get("trigger").asString()).isEqualTo("MANUAL");
        assertThat(accept.get("automatic").asBoolean()).isTrue();
        assertThat(accept.get("condition").asString()).isNotBlank();
        List<String> actors = new ArrayList<>();
        accept.get("actors").forEach(node -> actors.add(node.asString()));
        // In the order of RoleEnum, not alphabetically.
        assertThat(actors).containsExactly("MANAGER", "ADMIN");

        // A step with nothing else to hold has no condition key at all, not an empty string.
        assertThat(noCondition).isNotNull();
        assertThat(noCondition.has("condition") && !noCondition.get("condition").isNull()).isFalse();
    }
}
