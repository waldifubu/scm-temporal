package com.example.supplychainmanagement.security;

import com.example.supplychainmanagement.dto.auth.JwtAuthResponse;
import com.example.supplychainmanagement.dto.auth.LoginDto;
import com.example.supplychainmanagement.dto.auth.RegisterDto;
import com.example.supplychainmanagement.entity.usertypes.User;

public interface AuthService {
    User register(RegisterDto registerDto);

    JwtAuthResponse login(LoginDto loginDto);
}
