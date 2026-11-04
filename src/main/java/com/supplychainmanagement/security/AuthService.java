package com.supplychainmanagement.security;

import com.supplychainmanagement.dto.auth.AuthDtos.AuthResponse;
import com.supplychainmanagement.dto.auth.AuthDtos.LoginRequest;
import com.supplychainmanagement.dto.auth.AuthDtos.RegisterRequest;
import com.supplychainmanagement.entity.users.User;

public interface AuthService {
    User register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse logout();
}
