package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.support.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /me} against the real filter chain.
 * <p>
 * What makes it worth a context test is where the path does <em>not</em> live. It started as
 * {@code /auth/me}, and everything under {@code /auth} is public - register, login and logout have to
 * work without a token - so the JWT filter does not even run there and the path had to be carved out of
 * both the filter's skip list and the public matcher. As a resource of its own it needs none of that:
 * the filter runs and {@code anyRequest().authenticated()} applies. What is held here is that it does
 * (a token is really read), that the old path is gone, and that the public space was left alone.
 * <p>
 * The cookie case is the reason the endpoint exists at all: the token lives in an {@code HttpOnly}
 * cookie, JavaScript cannot read it, so after a reload asking the server is the only way to learn
 * whether there is a session.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class MeEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Value("${app.cookie.name}")
    private String cookieName;

    private String tokenFor(User user) {
        return jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
    }

    /** A call with its own rate-limit bucket, so the limiter never answers instead of the endpoint. */
    private MockHttpServletRequestBuilder get(String path) {
        return MockMvcRequestBuilders.get(path).header("X-API-KEY", "me-test-" + CALLS.incrementAndGet());
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    /**
     * The point of the endpoint: <strong>every</strong> role, not one. The login response named one
     * authority picked by iterator().next(), so this user came out as just LOGISTICS.
     */
    @Test
    void answersEveryRoleTheUserHolds() throws Exception {
        User user = testData.userWithRoles(RoleEnum.WAREHOUSE, RoleEnum.LOGISTICS);

        MockHttpServletResponse response = call(get("/api/1.0/me")
                .header("Authorization", "Bearer " + tokenFor(user)));

        assertThat(response.getStatus()).as("answered: %s", response.getContentAsString()).isEqualTo(200);
        String body = response.getContentAsString();
        // The enum names, exactly what @PreAuthorize("hasAnyAuthority(...)") compares against.
        List<String> roles = read(body, "$.roles[*]");
        assertThat(roles).containsExactlyInAnyOrder("WAREHOUSE", "LOGISTICS");
        assertThat((String) read(body, "$.username")).isEqualTo(user.getUsername());
        assertThat((String) read(body, "$.email")).isEqualTo(user.getEmail());
        assertThat(((Number) read(body, "$.id")).longValue()).isEqualTo(user.getId());
    }

    /** The password hash never leaves, and neither does anything else the entity carries. */
    @Test
    void neverAnswersThePasswordHash() throws Exception {
        User user = testData.userWithRoles(RoleEnum.MANAGER);

        MockHttpServletResponse response = call(get("/api/1.0/me")
                .header("Authorization", "Bearer " + tokenFor(user)));
        String body = response.getContentAsString();

        // Asserted first: a 401 body does not contain the password either, so without this the checks
        // below pass on any failure and say nothing about the endpoint.
        assertThat(response.getStatus()).as("answered: %s", body).isEqualTo(200);
        assertThat(body).contains(user.getUsername());
        assertThat(body).doesNotContain("password").doesNotContain(user.getPassword());
    }

    /**
     * The browser's case: no Authorization header at all, only the HttpOnly cookie the login set.
     * Without this working the endpoint would be of no use to the client it was built for.
     */
    @Test
    void worksWithTheSessionCookieAlone() throws Exception {
        User user = testData.userWithRoles(RoleEnum.DISTRIBUTOR);

        MockHttpServletResponse response = call(get("/api/1.0/me")
                .cookie(new Cookie(cookieName, tokenFor(user))));

        assertThat(response.getStatus()).as("answered: %s", response.getContentAsString()).isEqualTo(200);
        assertThat((String) read(response.getContentAsString(), "$.username")).isEqualTo(user.getUsername());
    }

    /**
     * Roles are read from the database on every request, not frozen into the token: change them after
     * the token was issued and /me shows the change.
     */
    @Test
    void showsTheRolesAsTheyAreNowNotAsTheyWereAtLogin() throws Exception {
        User user = testData.userWithRoles(RoleEnum.WAREHOUSE);
        String token = tokenFor(user);
        // TestData creates a roles row only for the roles it is asked for, so another user holding
        // LOGISTICS makes sure the row exists - and hands over the real entity to assign.
        user.setRoles(new LinkedHashSet<>(testData.userWithRoles(RoleEnum.LOGISTICS).getRoles()));

        MockHttpServletResponse response = call(get("/api/1.0/me")
                .header("Authorization", "Bearer " + token));

        assertThat(response.getStatus()).as("answered: %s", response.getContentAsString()).isEqualTo(200);
        List<String> roles = read(response.getContentAsString(), "$.roles[*]");
        assertThat(roles).containsExactly("LOGISTICS");
    }

    /** No token: 401, the answer of every other protected endpoint. */
    @Test
    void answersWithoutATokenWith401() throws Exception {
        assertThat(call(get("/api/1.0/me")).getStatus()).isEqualTo(401);
    }

    /** A forged token is refused here too - proof that the filter really runs on this path. */
    @Test
    void refusesAForgedTokenWith401() throws Exception {
        User user = testData.userWithRoles(RoleEnum.WAREHOUSE);
        JwtTokenProvider forger = new JwtTokenProvider();
        org.springframework.test.util.ReflectionTestUtils.setField(forger, "jwtSecret",
                "a-different-key-that-is-long-enough-to-sign-with-hmac");
        org.springframework.test.util.ReflectionTestUtils.setField(forger, "jwtExpirationInMs", 60_000L);
        String forged = forger.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));

        assertThat(call(get("/api/1.0/me").header("Authorization", "Bearer " + forged)).getStatus())
                .isEqualTo(401);
    }

    /** The version is optional like everywhere else: ApiVersionDefaultFilter rewrites /api/me. */
    @Test
    void answersWithoutAVersionInThePath() throws Exception {
        User user = testData.userWithRoles(RoleEnum.MANAGER);

        MockHttpServletResponse response = call(get("/api/me")
                .header("Authorization", "Bearer " + tokenFor(user)));

        assertThat(response.getStatus()).as("answered: %s", response.getContentAsString()).isEqualTo(200);
        assertThat((String) read(response.getContentAsString(), "$.username")).isEqualTo(user.getUsername());
    }

    /**
     * It moved, it was not copied: a client still calling {@code /auth/me} finds nothing there, rather
     * than something that works by accident.
     */
    @Test
    void noLongerAnswersUnderAuth() throws Exception {
        User user = testData.userWithRoles(RoleEnum.MANAGER);

        MockHttpServletResponse response = call(get("/api/1.0/auth/me")
                .header("Authorization", "Bearer " + tokenFor(user)));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).doesNotContain(user.getUsername());
    }

    // ------------------------------------------------------------------ the other direction

    /**
     * The public space is untouched. Logout answers without any token - a client whose session already
     * expired still has to be able to clear its cookie.
     */
    @Test
    void leavesLogoutPublic() throws Exception {
        assertThat(call(get("/api/1.0/auth/logout")).getStatus()).isEqualTo(200);
    }

    /** And login still works with no token, which is the whole reason /auth is public. */
    @Test
    void leavesLoginPublic() throws Exception {
        MockHttpServletResponse response = call(MockMvcRequestBuilders.post("/api/1.0/auth/login")
                .header("X-API-KEY", "me-test-" + CALLS.incrementAndGet())
                .contentType("application/json")
                .content("{\"usernameOrEmail\": \"nobody\", \"password\": \"x\"}"));

        // Refused for its credentials - which means it got as far as the login, not stopped at a gate.
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat((String) read(response.getContentAsString(), "$.message")).contains("Invalid username");
    }
}
