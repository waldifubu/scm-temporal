package com.supplychainmanagement.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CORS origin as configuration, without a context.
 * <p>
 * It was {@code List.of("http://localhost:3000")} in the code, so a client served from any other
 * origin - Vite's default port 5173 among them - was refused at the preflight and the only fix was a
 * rebuild. These hold the parsing and the shape of what is built from it; that a real preflight is
 * answered accordingly is {@code CorsPreflightTest}.
 */
class SecurityBeanConfigCorsTest {

    private final SecurityBeanConfig config = new SecurityBeanConfig();

    private CorsConfiguration built(String origins) {
        CorsConfigurationSource source = config.corsConfigurationSource(origins);
        return source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/1.0/shipments"));
    }

    // ------------------------------------------------------------------ the list

    @Test
    void splitsACommaSeparatedList() {
        assertThat(SecurityBeanConfig.parseOrigins("http://localhost:5173,http://localhost:3000"))
                .containsExactly("http://localhost:5173", "http://localhost:3000");
    }

    /** Whitespace around the commas is what people type, and what a properties file keeps. */
    @Test
    void trimsAndDropsBlanks() {
        assertThat(SecurityBeanConfig.parseOrigins("  http://a.example , ,http://b.example,  "))
                .containsExactly("http://a.example", "http://b.example");
    }

    /**
     * The Origin header never has a trailing slash, so an origin written with one would never match -
     * and the symptom is a 403 that says nothing about why.
     */
    @Test
    void stripsATrailingSlash() {
        assertThat(SecurityBeanConfig.parseOrigins("http://localhost:5173/"))
                .containsExactly("http://localhost:5173");
    }

    /** Empty is a value: no other origin. The default, and the one that fails closed. */
    @Test
    void treatsNothingAsNoOrigin() {
        assertThat(SecurityBeanConfig.parseOrigins(null)).isEmpty();
        assertThat(SecurityBeanConfig.parseOrigins("")).isEmpty();
        assertThat(SecurityBeanConfig.parseOrigins("   ,  ")).isEmpty();
    }

    /**
     * A wildcard cannot go with credentials, and the session here is a cookie. Refused at startup -
     * where it can be read - rather than as a failing request later.
     */
    @Test
    void refusesAWildcard() {
        assertThatThrownBy(() -> SecurityBeanConfig.parseOrigins("*"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
        assertThatThrownBy(() -> SecurityBeanConfig.parseOrigins("http://a.example,https://*.example"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ what is built

    @Test
    void allowsTheConfiguredOriginsAndNoOthers() {
        CorsConfiguration cors = built("http://localhost:5173");

        assertThat(cors.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        // The value that used to be written into the code is not special any more.
        assertThat(cors.checkOrigin("http://localhost:3000")).isNull();
    }

    /** Nothing configured allows nothing - not even the old default. */
    @Test
    void allowsNoOriginWhenNoneIsConfigured() {
        CorsConfiguration cors = built("");

        assertThat(cors.checkOrigin("http://localhost:5173")).isNull();
        assertThat(cors.checkOrigin("http://localhost:3000")).isNull();
    }

    /**
     * PATCH is allowed: PATCH /shipments/{id}/trackingnumber is one, and a preflight for a method that
     * is not listed is refused - the call would fail in the browser before it reached the server.
     */
    @Test
    void allowsPatchNextToTheOtherMethods() {
        CorsConfiguration cors = built("http://localhost:5173");

        assertThat(cors.getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }

    /** The session is a cookie, so credentials stay on - which is why the origin may never be a wildcard. */
    @Test
    void keepsCredentialsOn() {
        assertThat(built("http://localhost:5173").getAllowCredentials()).isTrue();
    }

    /** The list is what the property said, in order - nothing added behind its back. */
    @Test
    void addsNoOriginOfItsOwn() {
        List<String> allowed = built("http://a.example,http://b.example").getAllowedOrigins();

        assertThat(allowed).containsExactly("http://a.example", "http://b.example");
    }
}
