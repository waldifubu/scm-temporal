package com.supplychainmanagement.security;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two ways to register, through the real filter chain: {@code /auth/register} is the administrator's (any
 * role), {@code /auth/userregister} is open to everybody but never makes an ADMIN.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class RegisterEndpointsTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private UserRepository userRepository;

    /** The test schema starts without role rows; a user made with a role creates it. */
    @org.junit.jupiter.api.BeforeEach
    void rolesExist() {
        for (RoleEnum role : RoleEnum.values()) {
            testData.userWithRoles(role);
        }
    }

    private String body(String role) {
        long n = CALLS.incrementAndGet() + System.nanoTime() % 100000;
        return "{\"firstname\":\"Ada\",\"lastname\":\"Test\",\"username\":\"reg-" + n + "\",\"email\":\"reg-" + n
                + "@example.test\",\"password\":\"Secret-123\",\"role\":\"" + role + "\"}";
    }

    private MockHttpServletResponse post(String path, User as, String json) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post("/api/1.0/auth/" + path)
                .header("X-API-KEY", "reg-" + CALLS.incrementAndGet())
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (as != null) {
            request.header("Authorization", "Bearer " + jwtTokenProvider.generateToken(
                    new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of())));
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    @Test
    void registerIsForAdministratorsAndTakesAnyRole() throws Exception {
        assertThat(post("register", null, body("MANAGER")).getStatus()).as("anonymous").isEqualTo(401);
        for (RoleEnum role : List.of(RoleEnum.MANAGER, RoleEnum.CUSTOMER, RoleEnum.WAREHOUSE)) {
            assertThat(post("register", testData.userWithRoles(role), body("CUSTOMER")).getStatus())
                    .as(role.name()).isEqualTo(403);
        }
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        MockHttpServletResponse created = post("register", admin, body("ADMIN"));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        assertThat(post("register", admin, body("LOGISTICS")).getStatus()).isEqualTo(201);
    }

    @Test
    void userRegisterIsOpenButNeverMakesAnAdministrator() throws Exception {
        String refused = body("ADMIN");
        assertThat(post("userregister", null, refused).getStatus()).isEqualTo(403);
        assertThat(post("userregister", null, refused.replace("ADMIN", "admin")).getStatus()).isEqualTo(403);
        assertThat(userRepository.findAll().stream().map(User::getUsername)
                .filter(name -> refused.contains("\"" + name + "\""))).isEmpty();

        for (String role : List.of("CUSTOMER", "MANAGER", "SUPPLIER", "WAREHOUSE", "LOGISTICS", "DISTRIBUTOR")) {
            MockHttpServletResponse response = post("userregister", null, body(role));
            assertThat(response.getStatus()).as(role + ": " + response.getContentAsString()).isEqualTo(201);
        }
    }

    @Test
    void refusesAnUnknownRoleAndATakenUsername() throws Exception {
        assertThat(post("userregister", null, body("EMPEROR")).getStatus()).isEqualTo(400);

        String once = body("CUSTOMER");
        assertThat(post("userregister", null, once).getStatus()).isEqualTo(201);
        assertThat(post("userregister", null, once).getStatus()).isEqualTo(400);
    }
}
