package com.example.supplychainmanagement.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZonedDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class JwtAuthResponse {
    private static final String DEFAULT_TOKEN_TYPE = "Bearer";
    private String accessToken;
    private String tokenType = DEFAULT_TOKEN_TYPE;
    private ZonedDateTime expiresAt;
    private long expireAfterSeconds;
    private String role;
}
