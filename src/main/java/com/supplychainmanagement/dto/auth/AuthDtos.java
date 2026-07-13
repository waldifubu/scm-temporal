package com.supplychainmanagement.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

//@TODO: use it!
public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {}

    public record RegisterRequest(
            @NotBlank String username,
            @NotBlank @Size(min = 6, message = "Passwort muss mindestens 6 Zeichen haben") String password,
            @NotBlank String fullName,
            @Email String email) {}

    public record AuthResponse(
            String token,
            Long userId,
            String username,
            String fullName,
            List<String> roles) {}
}
