package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.RefreshTokenRepository;
import com.supplychainmanagement.support.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * The refresh token against the real filter chain and real rows.
 * <p>
 * The rules it holds: the login hands out a second, narrowly scoped cookie; a refresh trades it for a new pair
 * and retires the old token; a retired token shown again (outside the grace window) ends the whole family, the
 * newest token included; and a session ends when its idle window or its absolute lifetime does, at logout,
 * and for a disabled account. Time is moved with a clock instead of waited for.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class RefreshTokenEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();
    private static final String REFRESH_COOKIE = "scmrefresh";

    @Value("${app.cookie.name}")
    private String accessCookie;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-10T08:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private MovableClock clock;

    @BeforeEach
    void useAMovableClock() {
        clock = new MovableClock();
        refreshTokenService.setClock(clock);
    }

    @AfterEach
    void useTheRealClock() {
        refreshTokenService.setClock(Clock.systemUTC());
    }

    private User userWithPassword() {
        User user = testData.userWithRoles(RoleEnum.WAREHOUSE);
        user.setPassword(passwordEncoder.encode("secret"));
        return user;
    }

    private MockHttpServletResponse login(User user) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/1.0/auth/login")
                        .header("X-API-KEY", "refresh-" + CALLS.incrementAndGet())
                        .contentType(APPLICATION_JSON)
                        .content("{\"usernameOrEmail\": \"" + user.getUsername() + "\", \"password\": \"secret\"}"))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse refresh(String refreshToken) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/1.0/auth/refresh")
                .header("X-API-KEY", "refresh-" + CALLS.incrementAndGet());
        if (refreshToken != null) {
            request = request.cookie(new Cookie(REFRESH_COOKIE, refreshToken));
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse logout(String verb, String refreshToken) throws Exception {
        var request = "POST".equals(verb)
                ? MockMvcRequestBuilders.post("/api/1.0/auth/logout")
                : MockMvcRequestBuilders.get("/api/1.0/auth/logout");
        request = request.header("X-API-KEY", "refresh-" + CALLS.incrementAndGet());
        if (refreshToken != null) {
            request = request.cookie(new Cookie(REFRESH_COOKIE, refreshToken));
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    /** The whole {@code Set-Cookie} header of the cookie called {@code name}, or null. */
    private static String setCookie(MockHttpServletResponse response, String name) {
        List<String> headers = response.getHeaders("Set-Cookie");
        return headers.stream().filter(header -> header.startsWith(name + "=")).findFirst().orElse(null);
    }

    /** The value of a cookie out of its {@code Set-Cookie} header. */
    private static String valueOf(MockHttpServletResponse response, String name) {
        String header = setCookie(response, name);
        assertThat(header).as("Set-Cookie for " + name).isNotNull();
        String pair = header.substring(0, header.indexOf(';') < 0 ? header.length() : header.indexOf(';'));
        return pair.substring(name.length() + 1);
    }

    // ------------------------------------------------------------------ login

    @Test
    void aLoginHandsOutAnHttpOnlyStrictRefreshCookieScopedToTheAuthSpace() throws Exception {
        MockHttpServletResponse login = login(userWithPassword());

        assertThat(login.getStatus()).isEqualTo(200);
        String cookie = setCookie(login, REFRESH_COOKIE);
        assertThat(cookie).isNotNull()
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Path=/api/1.0/auth")
                .contains("Max-Age=" + Duration.ofDays(7).toSeconds());
        assertThat(setCookie(login, accessCookie)).isNotNull();
    }

    @Test
    void theRefreshTokenIsNotInTheBodyAndTheDatabaseHoldsOnlyItsHash() throws Exception {
        MockHttpServletResponse login = login(userWithPassword());
        String token = valueOf(login, REFRESH_COOKIE);

        assertThat(login.getContentAsString()).doesNotContain(token);
        assertThat(refreshTokenRepository.findByTokenHash(token)).isEmpty();
        assertThat(refreshTokenRepository.findByTokenHash(RefreshTokenService.hash(token))).isPresent();
    }

    // ------------------------------------------------------------------ refresh

    @Test
    void aRefreshAnswersANewPairAndTheNewAccessTokenWorks() throws Exception {
        User user = userWithPassword();
        String first = valueOf(login(user), REFRESH_COOKIE);

        MockHttpServletResponse refreshed = refresh(first);

        assertThat(refreshed.getStatus()).isEqualTo(200);
        assertThat(refreshed.getContentAsString()).contains(user.getUsername()).doesNotContain(first);
        assertThat(valueOf(refreshed, REFRESH_COOKIE)).isNotEqualTo(first);
        String access = valueOf(refreshed, accessCookie);
        MockHttpServletResponse me = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/me")
                        .header("X-API-KEY", "refresh-" + CALLS.incrementAndGet())
                        .cookie(new Cookie(accessCookie, access)))
                .andReturn().getResponse();
        assertThat(me.getStatus()).isEqualTo(200);
    }

    @Test
    void aRefreshWithoutACookieOrWithAnUnknownOneIs401() throws Exception {
        assertThat(refresh(null).getStatus()).isEqualTo(401);
        assertThat(refresh("not-a-token-anybody-issued").getStatus()).isEqualTo(401);
    }

    @Test
    void theAccessTokenCookieDoesNotStandInForTheRefreshToken() throws Exception {
        String access = valueOf(login(userWithPassword()), accessCookie);

        assertThat(refresh(access).getStatus()).isEqualTo(401);
    }

    @Test
    void aRefreshTokenAnsweredTwiceInsideTheGraceWindowStillWorks() throws Exception {
        String first = valueOf(login(userWithPassword()), REFRESH_COOKIE);

        assertThat(refresh(first).getStatus()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(5));

        assertThat(refresh(first).getStatus()).isEqualTo(200);
    }

    @Test
    void aRetiredTokenShownAgainEndsTheWholeFamilyTheNewestTokenIncluded() throws Exception {
        String first = valueOf(login(userWithPassword()), REFRESH_COOKIE);
        String second = valueOf(refresh(first), REFRESH_COOKIE);
        clock.advance(Duration.ofMinutes(1));

        MockHttpServletResponse replay = refresh(first);

        assertThat(replay.getStatus()).isEqualTo(401);
        // The owner's own, still unused token is gone with it - who holds the copy cannot be told.
        assertThat(refresh(second).getStatus()).isEqualTo(401);
    }

    @Test
    void sessionsOfOtherLoginsAreNotTouchedByARevokedFamily() throws Exception {
        User user = userWithPassword();
        String first = valueOf(login(user), REFRESH_COOKIE);
        String otherBrowser = valueOf(login(user), REFRESH_COOKIE);
        refresh(first);
        clock.advance(Duration.ofMinutes(1));
        refresh(first);

        assertThat(refresh(otherBrowser).getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ lifetimes

    @Test
    void aTokenNobodyUsedForTheIdleWindowIsDead() throws Exception {
        String token = valueOf(login(userWithPassword()), REFRESH_COOKIE);
        clock.advance(Duration.ofDays(7).plusMinutes(1));

        assertThat(refresh(token).getStatus()).isEqualTo(401);
    }

    @Test
    void aBusySessionStillEndsAtTheAbsoluteLimit() throws Exception {
        String token = valueOf(login(userWithPassword()), REFRESH_COOKIE);
        // Used every six days, so the idle window never runs out ...
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofDays(6));
            MockHttpServletResponse refreshed = refresh(token);
            assertThat(refreshed.getStatus()).as("refresh %d", i + 1).isEqualTo(200);
            token = valueOf(refreshed, REFRESH_COOKIE);
        }
        // ... and on day 30.5 the thirty days of the login are over anyway, although the token was issued
        // on day 24, well inside its seven.
        clock.advance(Duration.ofDays(6).plusHours(12));

        assertThat(refresh(token).getStatus()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ the account

    @Test
    void aDisabledAccountCannotRefreshAndGetsTheLoginsOwn403() throws Exception {
        User user = userWithPassword();
        String token = valueOf(login(user), REFRESH_COOKIE);
        user.setIsActive(false);

        assertThat(refresh(token).getStatus()).isEqualTo(403);
    }

    @Test
    void endingAllSessionsOfAUserKillsEveryRefreshToken() throws Exception {
        User user = userWithPassword();
        String one = valueOf(login(user), REFRESH_COOKIE);
        String two = valueOf(login(user), REFRESH_COOKIE);

        refreshTokenService.revokeAllOf(user.getId());

        assertThat(refresh(one).getStatus()).isEqualTo(401);
        assertThat(refresh(two).getStatus()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ logout

    @Test
    void logoutByPostEndsTheSessionAndClearsBothCookies() throws Exception {
        String token = valueOf(login(userWithPassword()), REFRESH_COOKIE);

        MockHttpServletResponse out = logout("POST", token);

        assertThat(out.getStatus()).isEqualTo(200);
        assertThat(setCookie(out, REFRESH_COOKIE)).contains("Max-Age=0");
        assertThat(setCookie(out, accessCookie)).contains("Max-Age=0");
        assertThat(refresh(token).getStatus()).isEqualTo(401);
    }

    @Test
    void logoutByGetStillWorksAndEndsTheSessionToo() throws Exception {
        String token = valueOf(login(userWithPassword()), REFRESH_COOKIE);

        assertThat(logout("GET", token).getStatus()).isEqualTo(200);
        assertThat(refresh(token).getStatus()).isEqualTo(401);
    }

    @Test
    void logoutWithoutARefreshCookieStillSucceeds() throws Exception {
        assertThat(logout("POST", null).getStatus()).isEqualTo(200);
    }
}
