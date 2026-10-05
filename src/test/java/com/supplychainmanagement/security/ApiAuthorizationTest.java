package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * The role matrix, against the real security filter chain - the one thing no other test in this
 * project touches.
 * <p>
 * Every controller test here uses standalone MockMvc, which has no filters at all, so until now
 * <strong>every {@code @PreAuthorize} in the codebase was unverified</strong>. The whole
 * WAREHOUSE/LOGISTICS split, the ADMIN exemptions, the work lists each role got of its own: all of
 * it was read, never checked.
 * <p>
 * <strong>Why real JWTs and not {@code @WithMockUser}.</strong> {@code JwtAuthenticationFilter}
 * demands a token on every {@code /api/**} request - without one it throws
 * {@code BadCredentialsException}, clears the context and answers before the controller is reached,
 * so a mock security context would simply be wiped. The token itself only carries the user name; the
 * filter then loads the user and takes the authorities from the <strong>database</strong>. A test
 * therefore needs a real user with real {@code roles} rows, which is what
 * {@link TestData#userWithRoles} builds.
 * <p>
 * <strong>What is asserted.</strong> Denied is {@code 403}. Allowed is <em>anything but</em>
 * {@code 403} - most of these calls then fail on business grounds (404 for an id that does not
 * exist, 409 for a wrong status), and that is the point: this test is about who may knock, not about
 * what is behind the door. Asserting 200 would mean building a whole fixture per endpoint and would
 * fail for reasons that have nothing to do with authorization.
 * <p>
 * A POST that needs a body gets a valid one even in the denied case. Argument resolution runs
 * <em>before</em> the security interceptor, so a missing body would answer 400 and the test would
 * never find out whether the role was refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class ApiAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /** Counts calls so each one gets its own rate-limit bucket - see {@link #call}. */
    private static final AtomicLong API_CALLS = new AtomicLong();

    /**
     * One endpoint and the roles that may reach it.
     *
     * @param body a syntactically valid body, or null for a call that takes none
     */
    private record Endpoint(HttpMethod method, String path, String body, Set<RoleEnum> allowed) {

        @Override
        public String toString() {
            return method + " " + path + " -> " + allowed;
        }
    }

    private static Endpoint get(String path, RoleEnum... allowed) {
        return new Endpoint(HttpMethod.GET, path, null, EnumSet.copyOf(List.of(allowed)));
    }

    private static Endpoint post(String path, String body, RoleEnum... allowed) {
        return new Endpoint(HttpMethod.POST, path, body, EnumSet.copyOf(List.of(allowed)));
    }

    /**
     * The endpoints whose role rules were decided deliberately - the ones worth a regression test.
     * Not every endpoint in the application: a table nobody maintains is worse than a short one that
     * is true.
     */
    private static Stream<Endpoint> matrix() {
        return Stream.of(
                // The clean split: packing work is the warehouse's, planning is logistics'.
                get("/api/1.0/packages", RoleEnum.ADMIN, RoleEnum.WAREHOUSE),
                get("/api/1.0/shipment-packages", RoleEnum.ADMIN, RoleEnum.WAREHOUSE),
                get("/api/1.0/shipments", RoleEnum.ADMIN, RoleEnum.LOGISTICS),
                // The planning side's own view of what it may ship - the overlap's replacement.
                get("/api/1.0/shipments/packages", RoleEnum.ADMIN, RoleEnum.LOGISTICS),
                // The carrier reports; the house plans.
                get("/api/1.0/shipments/distributor", RoleEnum.ADMIN, RoleEnum.DISTRIBUTOR),
                post("/api/1.0/shipments/1/accept", null, RoleEnum.ADMIN, RoleEnum.DISTRIBUTOR),
                post("/api/1.0/shipments/1/intransit", null, RoleEnum.ADMIN, RoleEnum.DISTRIBUTOR),
                post("/api/1.0/shipments/1/delivered", null, RoleEnum.ADMIN, RoleEnum.DISTRIBUTOR),
                post("/api/1.0/shipments/1/trackingnumber", "{\"trackingNumber\": \"DHL-123\"}",
                        RoleEnum.ADMIN, RoleEnum.DISTRIBUTOR),
                // One cancel for both sides, which was two endpoints on one path before.
                post("/api/1.0/shipments/1/cancel", "{\"reason\": \"no truck\"}",
                        RoleEnum.ADMIN, RoleEnum.LOGISTICS, RoleEnum.DISTRIBUTOR),
                // The component catalogue: the warehouse reads it because it orders parts.
                get("/api/1.0/components", RoleEnum.ADMIN, RoleEnum.MANAGER, RoleEnum.WAREHOUSE),
                // The warehouse's work list and the supplier's own - two views of the same rows.
                get("/api/1.0/components/requests", RoleEnum.ADMIN, RoleEnum.WAREHOUSE),
                get("/api/1.0/supplier/my-requests", RoleEnum.ADMIN, RoleEnum.SUPPLIER),
                post("/api/1.0/components/request/1",
                        "[{\"componentId\": \"706a99c3-944b-11f1-9b51-001e064520d8\", \"qty\": 1}]",
                        RoleEnum.ADMIN, RoleEnum.MANAGER, RoleEnum.WAREHOUSE),
                // What the supplier reports, and the goods receipt that answers it.
                post("/api/1.0/supplier/1/approve", null, RoleEnum.ADMIN, RoleEnum.SUPPLIER),
                post("/api/1.0/supplier/1/intransit", null, RoleEnum.ADMIN, RoleEnum.SUPPLIER),
                post("/api/1.0/supplier/1/delivered", null, RoleEnum.ADMIN, RoleEnum.SUPPLIER),
                post("/api/1.0/supplier/1/reject", null, RoleEnum.ADMIN, RoleEnum.SUPPLIER),
                post("/api/1.0/supplier/1/cancel", null, RoleEnum.ADMIN, RoleEnum.SUPPLIER),
                post("/api/1.0/components/warehouse/1/in-stock/1", null, RoleEnum.ADMIN, RoleEnum.WAREHOUSE),
                // Writing stock is the warehouse's everywhere.
                post("/api/1.0/stock/add",
                        "{\"sku\": \"706a99c3-944b-11f1-9b51-001e064520d8\", \"storehouseId\": 1, \"qty\": 1}",
                        RoleEnum.ADMIN, RoleEnum.WAREHOUSE),
                // Ending an order is commercial - deliberately not the customer's.
                post("/api/1.0/orders/1/complete", null, RoleEnum.ADMIN, RoleEnum.MANAGER),
                post("/api/1.0/orders/1/cancel", null, RoleEnum.ADMIN, RoleEnum.MANAGER),
                // Reading one order is wide, including LOGISTICS, which needs the dates.
                get("/api/1.0/orders/1", RoleEnum.ADMIN, RoleEnum.MANAGER, RoleEnum.WAREHOUSE,
                        RoleEnum.CUSTOMER, RoleEnum.LOGISTICS),
                get("/api/1.0/users", RoleEnum.ADMIN, RoleEnum.MANAGER)
        );
    }

    /** A bearer token for a freshly created user holding exactly that role. */
    private String tokenFor(RoleEnum role) {
        User user = testData.userWithRoles(role);
        return jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
    }

    private MvcResult call(Endpoint endpoint, RoleEnum as) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders
                .request(endpoint.method(), endpoint.path())
                .header("Authorization", "Bearer " + tokenFor(as))
                // A bucket of its own per call. RateLimitingFilter keys by X-API-KEY and falls back
                // to the remote address, so without this the whole matrix shares one FREE bucket and
                // starts answering 429 - which a test about roles would read as "not refused".
                // The filter stays in the chain; it is simply never the thing that answers.
                .header("X-API-KEY", "authz-test-" + API_CALLS.incrementAndGet());
        if (endpoint.body() != null) {
            request = request.contentType(APPLICATION_JSON).content(endpoint.body());
        }
        return mockMvc.perform(request).andReturn();
    }

    /** Every role the endpoint names gets past the gate - whatever happens to it afterwards. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void letsTheNamedRolesThrough(Endpoint endpoint) throws Exception {
        for (RoleEnum allowed : endpoint.allowed()) {
            int status = call(endpoint, allowed).getResponse().getStatus();

            assertThat(status)
                    .as("%s should let %s through, got %d", endpoint, allowed, status)
                    .isNotEqualTo(HttpStatus.FORBIDDEN.value());
        }
    }

    /** And every role it does not name is refused with 403, not merely unlucky downstream. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void refusesEveryOtherRoleWith403(Endpoint endpoint) throws Exception {
        for (RoleEnum denied : EnumSet.complementOf(EnumSet.copyOf(endpoint.allowed()))) {
            int status = call(endpoint, denied).getResponse().getStatus();

            assertThat(status)
                    .as("%s should refuse %s with 403, got %d", endpoint, denied, status)
                    .isEqualTo(HttpStatus.FORBIDDEN.value());
        }
    }

    /** Without a token nothing under /api is reachable - the filter answers before the controller. */
    @Test
    void refusesARequestWithoutAToken() throws Exception {
        int status = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/shipments"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }

    /**
     * The sanity check for the whole class: a token for a user with no role at all must not reach a
     * single endpoint. If this ever passes something, the matrix above is measuring nothing.
     */
    @Test
    void refusesAUserWithoutAnyRole() throws Exception {
        User nobody = testData.userWithRoles();
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(nobody.getUsername(), null, List.of()));

        int status = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/shipments")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(HttpStatus.FORBIDDEN.value());
    }
}
