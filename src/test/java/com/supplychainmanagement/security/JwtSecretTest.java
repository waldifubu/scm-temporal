package com.supplychainmanagement.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That the application can sign a token with the key it ships with.
 * <p>
 * It could not. {@code app.jwtSecret} was {@code SupplyChainManagementSecretKey} - 30 characters,
 * 240 bits - and HMAC-SHA requires 256 (RFC 7518: the key must be at least as long as the hash
 * output). {@code Keys.hmacShaKeyFor} therefore threw {@code WeakKeyException} from inside
 * {@code generateToken}, so the application started cleanly and <strong>every login answered
 * 500</strong> - on every profile except {@code dev}, which happens to carry a long key.
 * <p>
 * Why nothing noticed for so long, and why this test is shaped the way it is: the application is
 * always started with {@code -Dspring-boot.run.profiles=dev}, and {@code ApplicationTests} is the
 * only test that loads the context without a profile - but it never mints a token. A test that
 * merely booted the context would not have caught it either. So this one reads the shipped property
 * file directly, no context and no database, and checks the one thing that matters: the key the
 * application falls back to is long enough to sign with.
 */
class JwtSecretTest {

    /**
     * The <strong>template</strong>, not the live file. {@code application.properties} is gitignored
     * (it holds the real datasource and secret), so reading it would make this test pass or fail
     * depending on what happens to be on the machine - and fail outright in a fresh clone, where the
     * file does not exist. {@code application.properties.dist} is what everyone copies to create it,
     * so it is the value that actually ships.
     */
    private static String templateSecret() throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(Path.of("src/main/resources/application.properties.dist"))) {
            properties.load(in);
        }
        return properties.getProperty("app.jwtSecret");
    }

    /** Resolves {@code ${JWT_SECRET:default}} to its default - the value used when nothing is set. */
    private static String fallbackOf(String value) {
        Matcher placeholder = Pattern.compile("^\\$\\{[^:}]+:(.*)}$").matcher(value);
        return placeholder.matches() ? placeholder.group(1) : value;
    }

    /**
     * The finding itself: without the environment variable the shipped default has to be able to
     * sign, or no deployment works without a profile that happens to override it.
     */
    @Test
    void theShippedSecretCanSignAToken() throws IOException {
        String configured = templateSecret();

        assertThat(configured).as("app.jwtSecret is missing from application.properties.dist").isNotNull();

        String fallback = fallbackOf(configured);
        int bytes = fallback.getBytes(StandardCharsets.UTF_8).length;

        assertThat(bytes)
                .as("the default of %s is %d bytes (%d bits) - HMAC-SHA needs %d bytes",
                        configured, bytes, bytes * 8, JwtTokenProvider.MIN_SECRET_BYTES)
                .isGreaterThanOrEqualTo(JwtTokenProvider.MIN_SECRET_BYTES);
    }

    /**
     * The end of the chain, and the only assertion that really answers the finding: a token is
     * actually produced with the shipped key. Length alone was never the question - signing was.
     */
    @Test
    void reallySignsATokenWithTheShippedSecret() throws IOException {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", fallbackOf(templateSecret()));
        ReflectionTestUtils.setField(provider, "jwtExpirationInMs", 1_800_000L);

        String token = provider.generateToken(
                new UsernamePasswordAuthenticationToken("someone", null, List.of()));

        assertThat(token).isNotBlank();
        // And it can be read back, so the key is consistent in both directions.
        assertThat(provider.getUsername(token)).isEqualTo("someone");
    }

    /**
     * The template ships a placeholder that says what to do with it - it must not look like a real
     * key somebody might leave in place.
     */
    @Test
    void theTemplateSecretIsRecognisablyAPlaceholder() throws IOException {
        assertThat(templateSecret().toUpperCase()).contains("CHANGE");
    }

    /** A short key fails at startup, where it can be read, instead of at the first login. */
    @ParameterizedTest
    @ValueSource(strings = {"", "SupplyChainManagementSecretKey", "31-characters-is-one-too-few!!!"})
    void refusesAnUnusableSecretAtStartup(String tooShort) {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", tooShort);

        assertThatThrownBy(provider::requireUsableSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.jwtSecret")
                .hasMessageContaining("JWT_SECRET");
    }

    /** Exactly the minimum is enough - it is a minimum, not a boundary to stay above. */
    @Test
    void acceptsTheShortestUsableSecret() {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", "a".repeat(JwtTokenProvider.MIN_SECRET_BYTES));

        assertThatCode(provider::requireUsableSecret).doesNotThrowAnyException();
    }

    /** A missing property is the same failure, not a NullPointerException. */
    @Test
    void refusesAMissingSecret() {
        JwtTokenProvider provider = new JwtTokenProvider();

        assertThatThrownBy(provider::requireUsableSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0 bytes");
    }
}
