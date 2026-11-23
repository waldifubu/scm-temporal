package com.supplychainmanagement.security;

import com.supplychainmanagement.controller.RuleController;
import com.supplychainmanagement.entity.DistributorRule;
import com.supplychainmanagement.entity.SupplierRule;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.DistributorRuleRepository;
import com.supplychainmanagement.repository.SupplierRuleRepository;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code GET /supplier-rules} and {@code GET /distributor-rules}: ADMIN only, real rows, every sort key. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class RuleListEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private SupplierRuleRepository supplierRules;
    @Autowired
    private DistributorRuleRepository distributorRules;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ObjectMapper objectMapper;

    private MockHttpServletResponse get(String pathAndQuery, RoleEnum role) throws Exception {
        User user = testData.userWithRoles(role);
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0" + pathAndQuery)
                        .header("X-API-KEY", "rules-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private JsonNode row(MockHttpServletResponse response, Long id) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        for (JsonNode row : objectMapper.readTree(response.getContentAsString()).get("content")) {
            if (row.get("id").asLong() == id) {
                return row;
            }
        }
        return null;
    }

    private SupplierRule supplierRule(boolean withConditions) {
        SupplierRule rule = new SupplierRule();
        rule.setSupplier(testData.supplier());
        if (withConditions) {
            rule.setComponent(testData.component());
            rule.setProduct(testData.product());
        }
        rule.setPriority(3);
        return supplierRules.saveAndFlush(rule);
    }

    private DistributorRule distributorRule(boolean withConditions) {
        DistributorRule rule = new DistributorRule();
        rule.setDistributor(testData.distributor());
        if (withConditions) {
            rule.setCustomer(testData.customer());
            rule.setMinWeight(new BigDecimal("10"));
            rule.setMaxVolume(new BigDecimal("5000"));
        }
        rule.setPriority(2);
        return distributorRules.saveAndFlush(rule);
    }

    @Test
    void listsSupplierRulesWithAndWithoutConditions() throws Exception {
        SupplierRule full = supplierRule(true);
        SupplierRule open = supplierRule(false);

        MockHttpServletResponse response = get("/supplier-rules?size=500", RoleEnum.ADMIN);
        JsonNode fullRow = row(response, full.getId());
        JsonNode openRow = row(response, open.getId());

        assertThat(fullRow.get("supplierId").asLong()).isEqualTo(full.getSupplier().getId());
        assertThat(fullRow.get("componentName").asString()).isEqualTo(full.getComponent().getName());
        assertThat(fullRow.get("productName").asString()).isEqualTo(full.getProduct().getName());
        assertThat(fullRow.get("priority").asInt()).isEqualTo(3);
        assertThat(openRow.get("componentId").isNull()).isTrue();
        assertThat(openRow.get("productId").isNull()).isTrue();
    }

    @Test
    void listsDistributorRulesWithTheirBounds() throws Exception {
        DistributorRule full = distributorRule(true);
        DistributorRule open = distributorRule(false);

        MockHttpServletResponse response = get("/distributor-rules?size=500", RoleEnum.ADMIN);
        JsonNode fullRow = row(response, full.getId());
        JsonNode openRow = row(response, open.getId());

        assertThat(fullRow.get("distributorId").asLong()).isEqualTo(full.getDistributor().getId());
        assertThat(fullRow.get("customerId").asLong()).isEqualTo(full.getCustomer().getId());
        assertThat(fullRow.get("minWeight").decimalValue()).isEqualByComparingTo("10");
        assertThat(fullRow.get("maxVolume").decimalValue()).isEqualByComparingTo("5000");
        assertThat(openRow.get("customerId").isNull()).isTrue();
    }

    /** Sorting through an optional association must not drop the rules that leave it open. */
    @Test
    void everySortKeyWorksAndKeepsTheOpenRules() throws Exception {
        SupplierRule open = supplierRule(false);
        DistributorRule openCarrier = distributorRule(false);

        for (String key : RuleController.SUPPLIER_RULE_SORTABLE) {
            for (String order : List.of("ASC", "DESC")) {
                MockHttpServletResponse response =
                        get("/supplier-rules?size=500&sort=" + key + "&order=" + order, RoleEnum.ADMIN);
                assertThat(row(response, open.getId())).as("supplier-rules " + key + " " + order).isNotNull();
            }
        }
        for (String key : RuleController.DISTRIBUTOR_RULE_SORTABLE) {
            for (String order : List.of("ASC", "DESC")) {
                MockHttpServletResponse response =
                        get("/distributor-rules?size=500&sort=" + key + "&order=" + order, RoleEnum.ADMIN);
                assertThat(row(response, openCarrier.getId()))
                        .as("distributor-rules " + key + " " + order).isNotNull();
            }
        }
    }

    @Test
    void refusesEveryoneButAdministrators() throws Exception {
        for (RoleEnum role : List.of(RoleEnum.MANAGER, RoleEnum.WAREHOUSE, RoleEnum.LOGISTICS, RoleEnum.CUSTOMER)) {
            assertThat(get("/supplier-rules", role).getStatus()).as("supplier-rules " + role).isEqualTo(403);
            assertThat(get("/distributor-rules", role).getStatus()).as("distributor-rules " + role).isEqualTo(403);
        }
    }

    private MockHttpServletResponse send(String method, String path, String body, RoleEnum role) throws Exception {
        User user = testData.userWithRoles(role);
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.request(
                                org.springframework.http.HttpMethod.valueOf(method), "/api/1.0" + path)
                        .header("X-API-KEY", "rules-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body))
                .andReturn().getResponse();
    }

    @Test
    void createsAndChangesASupplierRule() throws Exception {
        Long supplierId = testData.supplier().getId();
        Long componentId = testData.component().getId();

        MockHttpServletResponse created = send("POST", "/supplier-rules",
                "{\"supplierId\":" + supplierId + ",\"componentId\":" + componentId + ",\"priority\":4}", RoleEnum.ADMIN);
        assertThat(created.getStatus()).isEqualTo(201);
        long id = objectMapper.readTree(created.getContentAsString()).get("id").asLong();
        assertThat(supplierRules.findById(id)).isPresent();

        MockHttpServletResponse changed = send("PUT", "/supplier-rules/" + id,
                "{\"supplierId\":" + supplierId + ",\"priority\":9}", RoleEnum.ADMIN);
        assertThat(changed.getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(changed.getContentAsString());
        assertThat(body.get("priority").asInt()).isEqualTo(9);
        assertThat(body.get("componentId").isNull()).isTrue();
    }

    @Test
    void refusesASupplierRuleForSomebodyWhoIsNoSupplier() throws Exception {
        Long customerId = testData.customer().getId();

        assertThat(send("POST", "/supplier-rules", "{\"supplierId\":" + customerId + "}", RoleEnum.ADMIN).getStatus())
                .isEqualTo(400);
        assertThat(send("POST", "/supplier-rules", "{\"supplierId\":999999999}", RoleEnum.ADMIN).getStatus())
                .isEqualTo(404);
        assertThat(send("POST", "/supplier-rules", "{}", RoleEnum.ADMIN).getStatus()).isEqualTo(400);
    }

    @Test
    void createsAndChangesADistributorRuleAndChecksTheRange() throws Exception {
        Long distributorId = testData.distributor().getId();
        Long customerId = testData.customer().getId();

        MockHttpServletResponse created = send("POST", "/distributor-rules",
                "{\"distributorId\":" + distributorId + ",\"customerId\":" + customerId
                        + ",\"minWeight\":5,\"maxWeight\":50,\"priority\":1}", RoleEnum.ADMIN);
        assertThat(created.getStatus()).isEqualTo(201);
        long id = objectMapper.readTree(created.getContentAsString()).get("id").asLong();

        MockHttpServletResponse changed = send("PUT", "/distributor-rules/" + id,
                "{\"distributorId\":" + distributorId + ",\"minVolume\":100}", RoleEnum.ADMIN);
        assertThat(changed.getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(changed.getContentAsString());
        assertThat(body.get("customerId").isNull()).isTrue();
        assertThat(body.get("minWeight").isNull()).isTrue();

        // [min, max): an empty range would match nothing; a customer is not a carrier.
        assertThat(send("POST", "/distributor-rules", "{\"distributorId\":" + distributorId
                + ",\"minWeight\":50,\"maxWeight\":50}", RoleEnum.ADMIN).getStatus()).isEqualTo(400);
        assertThat(send("POST", "/distributor-rules", "{\"distributorId\":" + customerId + "}", RoleEnum.ADMIN)
                .getStatus()).isEqualTo(400);
    }

    @Test
    void deletesRulesAndAnswers404ForAnUnknownOne() throws Exception {
        SupplierRule supplierRule = supplierRule(true);
        DistributorRule distributorRule = distributorRule(true);

        assertThat(send("DELETE", "/supplier-rules/" + supplierRule.getId(), "", RoleEnum.ADMIN).getStatus())
                .isEqualTo(200);
        assertThat(send("DELETE", "/distributor-rules/" + distributorRule.getId(), "", RoleEnum.ADMIN).getStatus())
                .isEqualTo(200);
        assertThat(supplierRules.findById(supplierRule.getId())).isEmpty();
        assertThat(distributorRules.findById(distributorRule.getId())).isEmpty();

        assertThat(send("DELETE", "/supplier-rules/" + supplierRule.getId(), "", RoleEnum.ADMIN).getStatus())
                .isEqualTo(404);
        assertThat(send("DELETE", "/distributor-rules/999999999", "", RoleEnum.ADMIN).getStatus()).isEqualTo(404);
    }

    @Test
    void onlyAdministratorsDeleteRules() throws Exception {
        SupplierRule rule = supplierRule(false);
        for (RoleEnum role : List.of(RoleEnum.MANAGER, RoleEnum.WAREHOUSE, RoleEnum.LOGISTICS)) {
            assertThat(send("DELETE", "/supplier-rules/" + rule.getId(), "", role).getStatus())
                    .as(role.name()).isEqualTo(403);
        }
        assertThat(supplierRules.findById(rule.getId())).isPresent();
    }

    @Test
    void onlyAdministratorsWriteRules() throws Exception {
        Long supplierId = testData.supplier().getId();
        for (RoleEnum role : List.of(RoleEnum.MANAGER, RoleEnum.WAREHOUSE, RoleEnum.LOGISTICS)) {
            assertThat(send("POST", "/supplier-rules", "{\"supplierId\":" + supplierId + "}", role).getStatus())
                    .as(role.name()).isEqualTo(403);
        }
    }

    @Test
    void answers400ForASortThatIsNotOnTheList() throws Exception {
        assertThat(get("/supplier-rules?sort=nope", RoleEnum.ADMIN).getStatus()).isEqualTo(400);
    }
}
