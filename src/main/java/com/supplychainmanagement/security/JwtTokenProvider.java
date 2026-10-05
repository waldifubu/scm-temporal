package com.supplychainmanagement.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import io.jsonwebtoken.security.WeakKeyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${app.jwtSecret}")
    private String jwtSecret;

    @Value("${app.jwtExpirationMs}")
    private long jwtExpirationInMs;

    @Value("${app.cookie.name}")
    private String cookieName;

    @Value("${app.cookie.secure}")
    private boolean secure;

    public String generateToken(Authentication authentication) {
        String username = authentication.getName();
        Date currentDate = new Date();
        Date expiryDate = new Date(currentDate.getTime() + jwtExpirationInMs);

        List<String> list = new ArrayList<>();
        for (GrantedAuthority grantedAuthority : authentication.getAuthorities()) {
            if (!Objects.equals(grantedAuthority.getAuthority(), "FACTOR_PASSWORD")) {
                String authority = grantedAuthority.getAuthority();
                list.add(authority);
            }
        }

        return Jwts.builder()
                .subject(username)
                .claim("authorities", list)
                .issuedAt(currentDate)
                .expiration(expiryDate)
                .signWith(key())
                .compact();
    }

    /**
     * The smallest key HMAC-SHA accepts: RFC 7518 requires the key to be at least as long as the
     * hash output, so 256 bits - 32 bytes - for HS256.
     */
    static final int MIN_SECRET_BYTES = 32;

    /**
     * Refuses a key that cannot sign, at startup instead of at the first login.
     * <p>
     * {@code app.jwtSecret} was 30 characters for a long time. {@code Keys.hmacShaKeyFor} then throws
     * {@code WeakKeyException} from deep inside {@code generateToken}, so the application started
     * cleanly and every login answered 500 - on every profile except dev, which happens to carry a
     * long key. Nothing noticed, because the application is always started with the dev profile and
     * the one test that loads the context without a profile never mints a token.
     * <p>
     * A key is either usable or the application cannot do its job, so this belongs in the startup
     * path. The message names the property and the actual length - the library's own talks about
     * byte arrays and JWA sections, which does not say which setting to change.
     */
    @PostConstruct
    void requireUsableSecret() {
        int bytes = jwtSecret == null ? 0 : jwtSecret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.jwtSecret is " + bytes + " bytes (" + bytes * 8
                    + " bits) and HMAC-SHA needs at least " + MIN_SECRET_BYTES + " bytes ("
                    + MIN_SECRET_BYTES * 8 + " bits). Set the JWT_SECRET environment variable to a"
                    + " longer value - with a shorter one no token can be issued at all.");
        }
    }

    private Key key() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String getUsername(String token) {
        return Jwts.parser()
                .verifyWith((SecretKey) key())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public Date getExpirationDate(String token) {
        return Jwts.parser()
                .verifyWith((SecretKey) key())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getExpiration();
    }

    // Create HttpOnly cookie
    public ResponseCookie generateJwtCookie(String token) {
        return ResponseCookie.from(cookieName, token)
                .httpOnly(true) // Prevent XSS
                .secure(secure)   // Send only over HTTPS
                .path("/")      // Accessible across all paths
                .maxAge(jwtExpirationInMs / 1000) // Cookie expiration (seconds)
                .sameSite("Lax") // CSRF protection
                .build();
    }

    public ResponseCookie removeJwtCookie() {
        return ResponseCookie.from(cookieName, "")
                .httpOnly(true) // Prevent XSS
                .secure(secure)   // Send only over HTTPS
                .path("/")      // Accessible across all paths
                .maxAge(0) // Cookie expiration (seconds)
                .sameSite("Lax") // CSRF protection
                .build();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith((SecretKey) key())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (ExpiredJwtException ex) {
            log.info("JWT token expired: {}", ex.getMessage());
            return false;
        } catch (SignatureException ex) {
            log.info("Invalid JWT token: {}", ex.getMessage());
            return false;
        } catch (WeakKeyException ex) {
            log.info("Weak key for JWT token: {}", ex.getMessage());
            return false;
        } catch (JwtException ex) {
            log.info("JWT token error: {}", ex.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Error validating JWT token: {}", e.getMessage());
            return false;
        }
    }
}
