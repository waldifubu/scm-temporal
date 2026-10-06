package com.supplychainmanagement.security;

import com.jayway.jsonpath.JsonPath;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * The status a client gets when authentication fails, against the real filter chain.
 * <p>
 * Nothing covered these paths, which is how they came to disagree with each other. A client has one
 * rule for authentication failure - <strong>401 means log in again</strong> - and the application
 * answered it three different ways: an expired token as 410, an unparseable one as 400, and a wrong
 * password as 400, while a missing, forged or unsupported token was already 401.
 * <p>
 * The login case has a second half that is about what the answer <em>says</em>, not its code: an
 * unknown user and a wrong password used to produce two different messages, so the endpoint told
 * anybody which user names exist. Those two are asserted equal here, not merely both 401.
 * <p>
 * What stays as it is, deliberately: a disabled account is a 403, and a locked one as well. They are
 * states of an account that is known, not failures of the credentials - and pinned below so a later
 * "tidy up" does not fold them into 401 and take away the one signal a client can show the user.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class AuthStatusCodesTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Value("${app.jwtSecret}")
    private String jwtSecret;

    /** A user whose password is "secret" - stored the way the application stores one. */
    private User userWithPassword() {
        User user = testData.userWithRoles(RoleEnum.WAREHOUSE);
        user.setPassword(passwordEncoder.encode("secret"));
        return user;
    }

    /** A login attempt. Each call gets its own rate-limit bucket, so the limiter never answers. */
    private MockHttpServletResponse login(String usernameOrEmail, String password) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/1.0/auth/login")
                        .header("X-API-KEY", "auth-status-" + CALLS.incrementAndGet())
                        .contentType(APPLICATION_JSON)
                        .content("{\"usernameOrEmail\": \"" + usernameOrEmail
                                + "\", \"password\": \"" + password + "\"}"))
                .andReturn().getResponse();
    }

    /** A protected call carrying the given Authorization header, or none. */
    private MockHttpServletResponse protectedCall(String authorization) throws Exception {
        var request = MockMvcRequestBuilders.get("/api/1.0/shipments")
                .header("X-API-KEY", "auth-status-" + CALLS.incrementAndGet());
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static String messageOf(MockHttpServletResponse response) throws Exception {
        return JsonPath.read(response.getContentAsString(), "$.message");
    }

    // ------------------------------------------------------------------ login

    /** Not a regression of the happy path - the same user and the right password still gets in. */
    @Test
    void acceptsTheRightPassword() throws Exception {
        User user = userWithPassword();

        assertThat(login(user.getUsername(), "secret").getStatus()).isEqualTo(200);
    }

    /** A wrong password is the credentials not being accepted: 401, not a malformed request. */
    @Test
    void answersAWrongPasswordWith401() throws Exception {
        User user = userWithPassword();

        MockHttpServletResponse response = login(user.getUsername(), "not-the-password");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(messageOf(response)).contains("Invalid username/email or password");
    }

    /**
     * The point of the change: asking for a user that does not exist must not look different from a
     * wrong password. Same status and the same text, so the endpoint cannot be used to list user names.
     */
    @Test
    void answersAnUnknownUserExactlyLikeAWrongPassword() throws Exception {
        User user = userWithPassword();

        MockHttpServletResponse wrongPassword = login(user.getUsername(), "not-the-password");
        MockHttpServletResponse unknownUser = login("nobody-by-this-name-" + CALLS.get(), "secret");

        assertThat(unknownUser.getStatus()).isEqualTo(401).isEqualTo(wrongPassword.getStatus());
        assertThat(messageOf(unknownUser)).isEqualTo(messageOf(wrongPassword));
    }

    /**
     * A disabled account stays a 403: it is a state of an account that exists, not a failure of the
     * credentials, and it is the signal a client shows the user instead of "try again".
     */
    @Test
    void keepsADisabledAccountAt403() throws Exception {
        User user = userWithPassword();
        user.setIsActive(false);

        assertThat(login(user.getUsername(), "secret").getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ token

    /** Without a token there is nothing to authenticate. */
    @Test
    void answersAMissingTokenWith401() throws Exception {
        assertThat(protectedCall(null).getStatus()).isEqualTo(401);
    }

    /**
     * An expired session is a 401 like every other token failure. It was 410, "gone for good", which
     * a session is not - and a client has one rule for it, log in again, whichever way the token failed.
     */
    @Test
    void answersAnExpiredTokenWith401() throws Exception {
        User user = userWithPassword();
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", jwtSecret);
        // An expiry one minute in the past: the token is valid in every other respect.
        ReflectionTestUtils.setField(provider, "jwtExpirationInMs", -60_000L);
        String expired = provider.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));

        MockHttpServletResponse response = protectedCall("Bearer " + expired);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(messageOf(response)).contains("expired");
    }

    /** The one token failure that was a 400: a token that cannot be parsed is not a malformed request. */
    @Test
    void answersAnUnparseableTokenWith401() throws Exception {
        assertThat(protectedCall("Bearer not.a.token").getStatus()).isEqualTo(401);
        assertThat(protectedCall("Bearer garbage").getStatus()).isEqualTo(401);
    }

    /** A token signed with another key never authenticates. */
    @Test
    void answersAForgedTokenWith401() throws Exception {
        User user = userWithPassword();
        JwtTokenProvider forger = new JwtTokenProvider();
        ReflectionTestUtils.setField(forger, "jwtSecret", "a-different-key-that-is-long-enough-to-sign-with-hmac");
        ReflectionTestUtils.setField(forger, "jwtExpirationInMs", 60_000L);
        String forged = forger.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));

        assertThat(protectedCall("Bearer " + forged).getStatus()).isEqualTo(401);
    }
}
