package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /orders} against the real filter chain, with the body the order form sends - the path
 * {@code /orders} itself, without a trailing slash.
 * <p>
 * The endpoint was mapped to {@code "/"}, and Spring does not match a trailing slash: the form got
 * "Method 'POST' is not supported" (a 405) for every order it tried to place, while the unit test of the
 * controller, which calls the method directly, could not know.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class OrderCreateEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private MockHttpServletResponse post(String path, User as, String json) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/1.0" + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("X-API-KEY", "order-create-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private String body(Product product, int quantity) {
        return "{\"dueDate\":\"" + LocalDate.now().plusDays(30) + "\",\"orderItems\":[{\"quantity\":" + quantity
                + ",\"product\":{\"articleNo\":" + product.getArticleNo() + "}}]}";
    }

    @Test
    void aCustomerPlacesAnOrderThroughThePathWithoutASlash() throws Exception {
        User customer = testData.userWithRoles(RoleEnum.CUSTOMER);

        MockHttpServletResponse response = post("/orders", customer, body(testData.product(), 2));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        assertThat(response.getContentAsString()).contains("orderNo");
    }
}
