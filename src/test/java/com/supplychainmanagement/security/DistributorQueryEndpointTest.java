package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.DistributorRule;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.DistributorRuleRepository;
import com.supplychainmanagement.support.TestData;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP {@code QUERY} method on {@code /users/distributors/query}, through the real filter chain: the firewall lets
 * the method in, the handler mapping routes it to the {@code RequestMethod.QUERY} method and to nothing else, the roles apply,
 * and the answer is the ranked carriers of the rules. {@code POST} on the same path says the same.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class DistributorQueryEndpointTest {

    private static final HttpMethod QUERY = HttpMethod.QUERY;
    private static final String PATH = "/api/1.0/users/distributors/query";
    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private DistributorRuleRepository rules;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ObjectMapper objectMapper;

    private MockHttpServletResponse send(HttpMethod method, String path, String body, RoleEnum role) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path)
                .header("X-API-KEY", "distributor-query-" + CALLS.incrementAndGet());
        if (role != null) {
            User user = testData.userWithRoles(role);
            String token = jwtTokenProvider.generateToken(
                    new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private DistributorRule rule(Distributor distributor, Customer customer, String minWeight) {
        DistributorRule rule = new DistributorRule();
        rule.setDistributor(distributor);
        rule.setCustomer(customer);
        rule.setMinWeight(minWeight == null ? null : new BigDecimal(minWeight));
        return rules.saveAndFlush(rule);
    }

    private List<Long> ids(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        List<Long> ids = new ArrayList<>();
        for (JsonNode row : objectMapper.readTree(response.getContentAsString()).get("content")) {
            ids.add(row.get("id").asLong());
        }
        return ids;
    }

    @Test
    void answersTheRankedCarriersOfTheRulesToAQueryWithABody() throws Exception {
        Customer customer = testData.customer();
        Distributor general = testData.distributor();
        Distributor forCustomer = testData.distributor();
        Distributor forCustomerAndHeavy = testData.distributor();
        rule(general, null, null);
        rule(forCustomer, customer, null);
        rule(forCustomerAndHeavy, customer, "50");

        String heavy = "{ \"customerId\": " + customer.getId() + ", \"weight\": 60 }";
        String light = "{ \"customerId\": " + customer.getId() + ", \"weight\": 10 }";

        assertThat(ids(send(QUERY, PATH, heavy, RoleEnum.LOGISTICS)))
                .containsSubsequence(forCustomerAndHeavy.getId(), forCustomer.getId(), general.getId());
        assertThat(ids(send(QUERY, PATH, light, RoleEnum.LOGISTICS)))
                .doesNotContain(forCustomerAndHeavy.getId())
                .containsSubsequence(forCustomer.getId(), general.getId());
    }

    @Test
    void everyCarrierNamesTheRuleThatPickedIt() throws Exception {
        Customer customer = testData.customer();
        Distributor carrier = testData.distributor();
        DistributorRule general = rule(carrier, null, null);
        DistributorRule specific = rule(carrier, customer, null);

        String body = "{ \"customerId\": " + customer.getId() + " }";
        JsonNode content = objectMapper.readTree(send(QUERY, PATH, body, RoleEnum.LOGISTICS).getContentAsString()).get("content");

        JsonNode found = null;
        for (JsonNode row : content) {
            if (row.get("id").asLong() == carrier.getId()) {
                found = row;
            }
        }
        // Named by two rules, it appears once, with the id of the better one - not of the last one looked at.
        assertThat(found).isNotNull();
        assertThat(found.get("ruleId").asLong()).isEqualTo(specific.getId()).isNotEqualTo(general.getId());
    }

    @Test
    void aNegativeWeightIsA400() throws Exception {
        assertThat(send(QUERY, PATH, "{ \"weight\": -1 }", RoleEnum.ADMIN).getStatus()).isEqualTo(400);
    }

    @Test
    void onlyTheRolesThatAssignACarrierMayAsk() throws Exception {
        assertThat(send(QUERY, PATH, "{}", RoleEnum.CUSTOMER).getStatus()).isEqualTo(403);
        assertThat(send(QUERY, PATH, "{}", RoleEnum.WAREHOUSE).getStatus()).isEqualTo(403);
        assertThat(send(QUERY, PATH, "{}", null).getStatus()).isEqualTo(401);
    }

    /** The mapping is by method: the method is not a wildcard, and no other path takes it. */
    @Test
    void getOnTheQueryPathAndQueryOnAnotherPathAreNotAnswered() throws Exception {
        // With a valid body: refused for its method, not merely for the missing one.
        assertThat(send(HttpMethod.GET, PATH, "{}", RoleEnum.ADMIN).getStatus()).isNotEqualTo(200);
        assertThat(send(HttpMethod.PUT, PATH, "{}", RoleEnum.ADMIN).getStatus()).isNotEqualTo(200);
        assertThat(send(QUERY, "/api/1.0/users/distributors", "{}", RoleEnum.ADMIN).getStatus()).isEqualTo(405);
    }

    @Test
    void aPreflightForQueryIsAnsweredLikeOneForTheOtherMethods() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.options(PATH)
                        .header("Origin", "http://app.test.example")
                        .header("Access-Control-Request-Method", "QUERY")
                        .header("Access-Control-Request-Headers", "content-type")
                        .header("X-API-KEY", "distributor-query-" + CALLS.incrementAndGet()))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("QUERY");
    }
}
