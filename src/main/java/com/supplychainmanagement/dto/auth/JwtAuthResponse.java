package com.supplychainmanagement.dto.auth;

import lombok.*;

import java.time.ZonedDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JwtAuthResponse {
    private static final String DEFAULT_TOKEN_TYPE = "Bearer";

    private String tokenType = DEFAULT_TOKEN_TYPE;
    private String token;
    private String username;
    private ZonedDateTime expiresAt;
    private long expireAfterSeconds;
    private String role;
    private String cookie;
}
