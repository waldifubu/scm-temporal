package com.supplychainmanagement.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing says anything about OpenAPI here - no property in the test profile, none on the class - and
 * the description must then not be served.
 * <p>
 * springdoc is on by default, and what it serves is every endpoint of the API with the roles that may
 * call it. An application whose properties never mention it must not publish that;
 * {@code openapi-defaults.properties} (ranked below {@code application.properties}) turns it off, and
 * this is what holds it. The development profile switches it on and is not active in tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void doesNotServeTheDescriptionByDefault() throws Exception {
        int status = mockMvc.perform(MockMvcRequestBuilders.get("/v3/api-docs")
                .header("X-API-KEY", "openapi-off-1")).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
    }

    @Test
    void doesNotServeSwaggerUiByDefault() throws Exception {
        int status = mockMvc.perform(MockMvcRequestBuilders.get("/swagger-ui/index.html")
                .header("X-API-KEY", "openapi-off-2")).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
    }
}
