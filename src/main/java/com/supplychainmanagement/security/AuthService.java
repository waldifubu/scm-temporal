package com.supplychainmanagement.security;

import com.supplychainmanagement.dto.auth.JwtAuthResponse;
import com.supplychainmanagement.dto.auth.LoginDto;
import com.supplychainmanagement.dto.auth.RegisterDto;
import com.supplychainmanagement.entity.users.User;

public interface AuthService {
    User register(RegisterDto registerDto);

    JwtAuthResponse login(LoginDto loginDto);

    JwtAuthResponse logout();
}
