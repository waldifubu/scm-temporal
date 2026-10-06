package com.supplychainmanagement.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configuration
public class SecurityBeanConfig {

    private static final String DEFAULT_PASSWORD_ENCODER_ID = "argon2";

    // Maybe use AuthenticationManagerBuilder with password encoder
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public static PasswordEncoder passwordEncoder() {

        Map<String, PasswordEncoder> encoders = Map.of(
                "argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                "bcrypt", new BCryptPasswordEncoder(),
                "pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()
        );

        return new DelegatingPasswordEncoder(DEFAULT_PASSWORD_ENCODER_ID, encoders);
    }

    /**
     * Which other origins a browser may call the API from, taken from {@code app.cors.allowedOrigins}.
     * <p>
     * It was one origin written into the code - {@code http://localhost:3000}. A client served from
     * anywhere else got a 403 at the preflight (Vite's default port, 5173, among them) and the only
     * fix was a rebuild. <strong>Empty is the default</strong>, and it means no other origin: a
     * deployment that serves the frontend from the same origin needs none, and one that forgets to say
     * which origin it uses fails closed rather than open. The template file carries the development
     * values.
     * <p>
     * PATCH is allowed next to the rest: {@code PATCH /shipments/&#123;id&#125;/trackingnumber} is one,
     * and a preflight for a method that is not listed is refused.
     * <p>
     * Credentials stay on - the session is a cookie - which is exactly why the origin must never be a
     * wildcard: a browser refuses {@code *} together with credentials, and allowing every site to make
     * credentialed calls would defeat {@code SameSite} entirely. {@link #parseOrigins} rejects it at
     * startup instead of letting a request fail later.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowedOrigins:}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(parseOrigins(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * A comma separated list of origins, cleaned up: blanks dropped, whitespace trimmed, and a
     * trailing slash removed - the {@code Origin} header never has one, so an origin written with it
     * would never match and fail without saying why.
     *
     * @throws IllegalStateException for {@code *}, which cannot go with credentials
     */
    static List<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .map(origin -> origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin)
                .peek(origin -> {
                    if (origin.contains("*")) {
                        throw new IllegalStateException("app.cors.allowedOrigins contains '" + origin
                                + "' - a wildcard cannot go with credentials. Name the origins, for example"
                                + " http://localhost:5173");
                    }
                })
                .toList();
    }
}
