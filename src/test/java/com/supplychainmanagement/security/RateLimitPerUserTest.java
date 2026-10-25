package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.PricingPlan;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate limit against the real filter chain: a logged-in user has a bucket of their own and a
 * generous one, an anonymous caller stays on the small per-address one, and neither a second user nor
 * an invented {@code X-API-KEY} changes what a user may do.
 * <p>
 * The limiter runs after Spring Security, so the user it keys by is the one the token authenticated - a
 * request Security refused (401) never reaches it and is not counted. The anonymous caller here comes
 * from an address of its own, since every MockMvc request is otherwise {@code 127.0.0.1} and the
 * buckets live as long as the context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class RateLimitPerUserTest {

    private static final AtomicLong KEYS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String tokenOfNewUser() {
        User user = testData.userWithRoles(RoleEnum.MANAGER);
        return jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
    }

    private MockHttpServletRequestBuilder me(String token, String apiKey) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get("/api/1.0/me")
                .header("Authorization", "Bearer " + token);
        return apiKey == null ? request : request.header("X-API-KEY", apiKey);
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void aUserMayCallAsOftenAsTheirPlanSaysAndNoMore() throws Exception {
        String token = tokenOfNewUser();
        int capacity = (int) PricingPlan.USER.getLimit().getCapacity();

        for (int call = 1; call <= capacity; call++) {
            assertThat(status(me(token, null))).as("call %d of %d", call, capacity).isEqualTo(200);
        }
        assertThat(status(me(token, null))).isEqualTo(429);
    }

    @Test
    void theUserPlanIsMoreThanTheFreeOnesBurst() throws Exception {
        String token = tokenOfNewUser();

        // The old limit for everybody: 30 in a burst. A page that fires a few dozen calls must not meet it.
        for (int call = 1; call <= 60; call++) {
            assertThat(status(me(token, null))).as("call %d", call).isEqualTo(200);
        }
    }

    @Test
    void oneUserUsingTheirBucketUpLeavesAnotherUsersAlone() throws Exception {
        String busy = tokenOfNewUser();
        String quiet = tokenOfNewUser();
        int capacity = (int) PricingPlan.USER.getLimit().getCapacity();
        for (int call = 0; call < capacity; call++) {
            status(me(busy, null));
        }
        assertThat(status(me(busy, null))).isEqualTo(429);

        assertThat(status(me(quiet, null))).isEqualTo(200);
    }

    @Test
    void anInventedKeyDoesNotGiveALoggedInUserANewBucket() throws Exception {
        String token = tokenOfNewUser();
        int capacity = (int) PricingPlan.USER.getLimit().getCapacity();
        for (int call = 0; call < capacity; call++) {
            status(me(token, "invented-" + KEYS.incrementAndGet()));
        }

        assertThat(status(me(token, "invented-" + KEYS.incrementAndGet()))).isEqualTo(429);
    }

    @Test
    void anonymousCallersStayOnTheSmallBucketPerAddress() throws Exception {
        String address = "10.%d.%d.%d".formatted(ThreadLocalRandom.current().nextInt(1, 250),
                ThreadLocalRandom.current().nextInt(1, 250), ThreadLocalRandom.current().nextInt(1, 250));
        int capacity = (int) PricingPlan.FREE.getLimit().getCapacity();

        int rejected = 0;
        for (int call = 0; call < capacity + 5; call++) {
            // The login is public, so the call reaches the limiter without a token.
            int status = mockMvc.perform(MockMvcRequestBuilders.post("/api/1.0/auth/login")
                            .contentType("application/json")
                            .content("{\"usernameOrEmail\":\"nobody-" + call + "\",\"password\":\"wrong-password\"}")
                            .with(r -> {
                                r.setRemoteAddr(address);
                                return r;
                            }))
                    .andReturn().getResponse().getStatus();
            if (status == 429) {
                rejected++;
            }
        }

        assertThat(rejected).isEqualTo(5);
    }
}
