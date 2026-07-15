package com.supplychainmanagement.controller;


import com.supplychainmanagement.dto.auth.JwtAuthResponse;
import com.supplychainmanagement.dto.auth.LoginDto;
import com.supplychainmanagement.dto.auth.RegisterDto;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.security.AuthService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<?> login(@RequestBody LoginDto loginDto) {

        try {
            JwtAuthResponse response = authService.login(loginDto);

//            eventPublisher.publishEvent(new UserLoginEvent(response.getUsername()));
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, response.getCookie())
                    .body(response);
        } catch (APIException apiException) {
            Map<String, String> response = new HashMap<>();
            response.put("message", apiException.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/logout")
    public ResponseEntity<?> logout() {
        try {
            var logoutResult = authService.logout();
            Map<String, String> response = new HashMap<>();
            response.put("message", "Logout successful");
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, logoutResult.getCookie())
                    .body(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }
}
