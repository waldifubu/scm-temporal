package com.example.supplychainmanagement.controller;


import com.example.supplychainmanagement.dto.auth.JwtAuthResponse;
import com.example.supplychainmanagement.dto.auth.LoginDto;
import com.example.supplychainmanagement.dto.auth.RegisterDto;
import com.example.supplychainmanagement.entity.usertypes.User;
import com.example.supplychainmanagement.security.AuthService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@AllArgsConstructor
public class AuthApiController {

    private AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterDto registerDto) {
        Map<String, String> response = new HashMap<>();
        User newUser;
        try {
            newUser = authService.register(registerDto);
        } catch (RuntimeException runtimeException) {
            response.put("message", runtimeException.getMessage());
            return ResponseEntity.badRequest().body(response);
        }

        response.put("message", "User registered successfully with username: " + newUser.getUsername() + ", and id: " + newUser.getId());
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<JwtAuthResponse> login(@RequestBody LoginDto loginDto) {
        JwtAuthResponse response = authService.login(loginDto);

        return ResponseEntity.ok(response);
    }
}
