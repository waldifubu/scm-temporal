package com.supplychainmanagement.dto.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotEmpty;
import lombok.Builder;

import java.time.ZonedDateTime;

public final class AuthDtos {

    private AuthDtos() {}

    /** Body of {@code POST /auth/login}; {@code usernameOrEmail} is matched against both columns. */
    public record LoginRequest(String usernameOrEmail, String password) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegisterRequest(
            @NotEmpty(message = "First name is required") String firstname,
            @NotEmpty(message = "Last name is required") String lastname,
            @NotEmpty(message = "Username is required") String username,
            @NotEmpty(message = "Email is required") String email,
            @NotEmpty(message = "Password is required") String password,
            @NotEmpty(message = "Role is required") String role,
            String color) {}

    /**
     * Answer of login and logout. {@code tokenType} never reaches the JSON; {@code cookie} is the
     * {@code Set-Cookie} value the controller moves into the header and is part of the body as before.
     */
    @Builder
    public record AuthResponse(
            @JsonIgnore String tokenType,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            String token,
            String username,
            ZonedDateTime expiresAt,
            long expireAfterSeconds,
            String role,
            String cookie,
            Long lastLogin) {}
}
