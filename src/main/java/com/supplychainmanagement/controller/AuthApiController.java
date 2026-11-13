package com.supplychainmanagement.controller;


import com.supplychainmanagement.dto.auth.AuthDtos.AuthResponse;
import com.supplychainmanagement.dto.auth.AuthDtos.LoginRequest;
import com.supplychainmanagement.dto.auth.AuthDtos.RegisterRequest;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.event.UserLoginEvent;
import com.supplychainmanagement.event.UserRegisteredEvent;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.security.AuthService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping({"/api/{version}/auth", "/api/auth"})
public class AuthApiController {

    private final ApplicationEventPublisher eventPublisher;
    private AuthService authService;

    @PostMapping(value = "/register", version = "1.0")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        Map<String, String> response = new HashMap<>();
        User newUser;
        try {
            newUser = authService.register(request);
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
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);

        eventPublisher.publishEvent(new UserLoginEvent(response.username()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, response.cookie(), response.refreshCookie())
                .body(response);
    }

    /**
     * A new access token for a refresh token, which arrives as a cookie only. It sits under {@code /auth} like
     * login and logout: it must work with an expired access token, which is the whole point of it. Any failure
     * is a 401 (log in again) except a disabled account, which stays the 403 the login gives.
     */
    @PostMapping(value = "/refresh", version = "1.0")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = "${app.refresh.cookie.name:scmrefresh}", required = false) String refreshToken) {
        AuthResponse response = authService.refresh(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, response.cookie(), response.refreshCookie())
                .body(response);
    }

    @Deprecated
    @PostMapping(value = "/login", version = "2.0")
    public ResponseEntity<?> login20(@RequestBody LoginRequest request) {
        try {
            String response = "2.0 login successful for user: " + request.usernameOrEmail();
            return ResponseEntity.ok()
                    .body(response);
        } catch (APIException apiException) {
            Map<String, String> response = new HashMap<>();
            response.put("message", apiException.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Ends the session: the access cookie goes, and so does the refresh family behind the refresh cookie. POST
     * is the verb that belongs to it; GET stays for clients written before, and does the same.
     */
    @RequestMapping(value = "/logout", method = {RequestMethod.GET, RequestMethod.POST}, version = "1.0")
    public ResponseEntity<?> logout(
            @CookieValue(name = "${app.refresh.cookie.name:scmrefresh}", required = false) String refreshToken) {
        try {
            var logoutResult = authService.logout(refreshToken);
            Map<String, String> response = new HashMap<>();
            response.put("message", "Logout successful");
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, logoutResult.cookie(), logoutResult.refreshCookie())
                    .body(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }
}

