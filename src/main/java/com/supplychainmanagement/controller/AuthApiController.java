package com.supplychainmanagement.controller;


import com.supplychainmanagement.dto.auth.JwtAuthResponse;
import com.supplychainmanagement.dto.auth.LoginDto;
import com.supplychainmanagement.dto.auth.RegisterDto;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.event.UserLoginEvent;
import com.supplychainmanagement.event.UserRegisteredEvent;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.security.AuthService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static reactor.netty.http.HttpConnectionLiveness.log;

@RestController
@AllArgsConstructor
@RequestMapping({"/api/{version}/auth", "/api/auth"})
public class AuthApiController {

    private final ApplicationEventPublisher eventPublisher;
    private AuthService authService;

    @PostMapping(value = "/register", version = "1.0")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterDto registerDto) {
        Map<String, String> response = new HashMap<>();
        User newUser;
        try {
            newUser = authService.register(registerDto);
        } catch (RuntimeException runtimeException) {
            response.put("message", runtimeException.getMessage());
            return ResponseEntity.badRequest().body(response);
        }

        response.put("message", "User successfully registered");
        log.info("User registered with username: {} and id: {}", newUser.getUsername(), newUser.getId());

        String roles = newUser.getRoles().stream()
                .map(role -> role.getRolename().name())
                .collect(Collectors.joining(", "));
        if (roles.isEmpty()) {
            roles = "No roles assigned";
        }

        eventPublisher.publishEvent(new UserRegisteredEvent(
                newUser.getUsername(),
                newUser.getEmail(),
                roles
        ));
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PostMapping(value = "/login", version = "1.0")
    @Deprecated
    public ResponseEntity<?> login(@RequestBody LoginDto loginDto) {
        try {
            JwtAuthResponse response = authService.login(loginDto);

            eventPublisher.publishEvent(new UserLoginEvent(response.getUsername()));
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, response.getCookie())
                    .body(response);
        } catch (APIException apiException) {
            Map<String, String> response = new HashMap<>();
            response.put("message", apiException.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping(value = "/login", version = "2.0")
    public ResponseEntity<?> login20(@RequestBody LoginDto loginDto) {
        try {
            String response = "2.0 login successful for user: " + loginDto.getUsernameOrEmail();
            return ResponseEntity.ok()
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

