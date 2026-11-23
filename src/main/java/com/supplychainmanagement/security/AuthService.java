package com.supplychainmanagement.security;

import com.supplychainmanagement.dto.auth.AuthDtos.AuthResponse;
import com.supplychainmanagement.dto.auth.AuthDtos.LoginRequest;
import com.supplychainmanagement.dto.auth.AuthDtos.RegisterRequest;
import com.supplychainmanagement.entity.users.User;

public interface AuthService {
    /** Creates a user with any role, ADMIN included - for an administrator creating an account. */
    User register(RegisterRequest request);

    /** Self-registration: any role but ADMIN (403), so nobody can make themselves an administrator. */
    User registerUser(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse logout();

    /** Logout that also ends the session behind a refresh token; {@code null} when the caller sent none. */
    AuthResponse logout(String refreshToken);

    /** Trades a refresh token for a new access token and the next refresh token. */
    AuthResponse refresh(String refreshToken);
}
