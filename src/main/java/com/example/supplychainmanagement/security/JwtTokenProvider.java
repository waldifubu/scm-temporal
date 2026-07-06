package com.example.supplychainmanagement.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import io.jsonwebtoken.security.WeakKeyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
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

    private Key key() {
//        Base64.Decoder decoder = Base64.getDecoder();
//        byte[] decodedSecret = decoder.decode(jwtSecret);
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
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
