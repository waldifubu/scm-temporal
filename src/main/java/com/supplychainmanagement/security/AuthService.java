package com.supplychainmanagement.security;

import com.supplychainmanagement.dto.auth.AuthDtos.AuthResponse;
import com.supplychainmanagement.dto.auth.AuthDtos.LoginRequest;
import com.supplychainmanagement.dto.auth.AuthDtos.RegisterRequest;
import com.supplychainmanagement.entity.users.User;

public interface AuthService {
    User register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse logout();

    /** Logout that also ends the session behind a refresh token; {@code null} when the caller sent none. */
    AuthResponse logout(String refreshToken);

    /** Trades a refresh token for a new access token and the next refresh token. */
    AuthResponse refresh(String refreshToken);
}
