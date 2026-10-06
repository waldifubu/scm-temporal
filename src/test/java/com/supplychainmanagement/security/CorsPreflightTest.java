package com.supplychainmanagement.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * A browser preflight against the real filter chain, with the origin taken from configuration.
 * <p>
 * {@code application-test.properties} allows {@code http://app.test.example} and nothing else - on
 * purpose not one of the development origins. That is what lets these tell "configured" from
 * "hard-coded": the value that used to be written into the code, {@code http://localhost:3000}, is
 * refused here, and the one the configuration names is let through.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsPreflightTest {

    private static final String ALLOWED = "http://app.test.example";
    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;

    private MockHttpServletRequestBuilder preflight(String origin, String method) {
        return MockMvcRequestBuilders.options("/api/1.0/shipments")
                .header("X-API-KEY", "cors-test-" + CALLS.incrementAndGet())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", "content-type");
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    /** The configured origin gets the headers a browser needs, credentials included. */
    @Test
    void letsTheConfiguredOriginThrough() throws Exception {
        MockHttpServletResponse response = call(preflight(ALLOWED, "GET"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    /**
     * What used to be the one allowed origin, written into the code. Refused now, which is the proof
     * that the origin comes from the configuration and not from there.
     */
    @Test
    void refusesTheOriginThatWasHardCoded() throws Exception {
        assertThat(call(preflight("http://localhost:3000", "GET")).getStatus()).isEqualTo(403);
    }

    /** Vite's default port was refused too - the reason this is configurable at all. */
    @Test
    void refusesAnOriginNobodyListed() throws Exception {
        MockHttpServletResponse response = call(preflight("http://localhost:5173", "GET"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    /**
     * PATCH is in the allowed methods: PATCH /shipments/{id}/trackingnumber is one, and a preflight
     * for a method that is not listed is refused before the call is ever made.
     */
    @Test
    void allowsAPatchPreflight() throws Exception {
        MockHttpServletResponse response = call(preflight(ALLOWED, "PATCH"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("PATCH");
    }

    /** A method nobody listed is still refused. */
    @Test
    void refusesAMethodNobodyListed() throws Exception {
        assertThat(call(preflight(ALLOWED, "TRACE")).getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ the dev proxy

    /**
     * The nuance behind "a proxy means no CORS". The browser sees one origin, but the <em>server</em>
     * still compares the Origin header with the request's own host: a proxy that keeps the original
     * Host (Vite's default, changeOrigin off) makes them equal, so there is nothing to check.
     * <p>
     * Origin and host agree, and the origin is not on the list - the request is not a CORS request at
     * all, so it is let through to the login, which then refuses its credentials with a 401.
     */
    @Test
    void doesNotTreatARequestWhoseOriginMatchesItsHostAsCors() throws Exception {
        MockHttpServletResponse response = call(MockMvcRequestBuilders.post("/api/1.0/auth/login")
                .header("X-API-KEY", "cors-test-" + CALLS.incrementAndGet())
                .header("Origin", "http://localhost:5173")
                .with(request -> {
                    request.setServerName("localhost");
                    request.setServerPort(5173);
                    return request;
                })
                .contentType(APPLICATION_JSON)
                .content("{\"usernameOrEmail\": \"nobody\", \"password\": \"x\"}"));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    /**
     * And the other half: a proxy that rewrites the Host to the backend's (changeOrigin on) makes the
     * same request look cross-origin, and it is refused unless the origin is listed. This is why the
     * development origins are in the template even though a proxy is the recommended setup.
     */
    @Test
    void doesTreatItAsCorsWhenTheProxyRewritesTheHost() throws Exception {
        MockHttpServletResponse response = call(MockMvcRequestBuilders.post("/api/1.0/auth/login")
                .header("X-API-KEY", "cors-test-" + CALLS.incrementAndGet())
                .header("Origin", "http://localhost:5173")
                .with(request -> {
                    request.setServerName("localhost");
                    request.setServerPort(8080);
                    return request;
                })
                .contentType(APPLICATION_JSON)
                .content("{\"usernameOrEmail\": \"nobody\", \"password\": \"x\"}"));

        assertThat(response.getStatus()).isEqualTo(403);
    }
}
