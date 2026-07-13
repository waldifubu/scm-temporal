package com.supplychainmanagement.security.impl;

import com.supplychainmanagement.dto.auth.JwtAuthResponse;
import com.supplychainmanagement.dto.auth.LoginDto;
import com.supplychainmanagement.dto.auth.RegisterDto;
import com.supplychainmanagement.entity.Role;
import com.supplychainmanagement.entity.users.*;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.RoleRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.security.AuthService;
import com.supplychainmanagement.security.JwtTokenProvider;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private static final Map<RoleEnum, Class<? extends User>> USER_TYPE_CLASS_BY_ROLE = Map.of(
            RoleEnum.ROLE_CUSTOMER, Customer.class,
            RoleEnum.ROLE_MANAGER, Manager.class,
            RoleEnum.ROLE_SUPPLIER, Supplier.class,
            RoleEnum.ROLE_WAREHOUSE, Warehouse.class,
            RoleEnum.ROLE_LOGISTICS, Logistics.class,
            RoleEnum.ROLE_DISTRIBUTOR, Distributor.class,
            RoleEnum.ROLE_ADMIN, Admin.class
    );

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${application.timezone:UTC}")
    private String applicationTimeZone;

    @Value("${app.jwtCookieName}")
    private String cookieName;

    @Override
    public User register(RegisterDto registerDto) {
        if (userRepository.existsByUsername(registerDto.getUsername())) {
            throw new APIException(
                    HttpStatus.BAD_REQUEST,
                    "Username already taken!"
            );
        }

        if (userRepository.existsByEmail(registerDto.getEmail())) {
            throw new APIException(
                    HttpStatus.BAD_REQUEST,
                    "Email already taken!"
            );
        }

        var user = new User();
        user.setFirstName(registerDto.getFirstname());
        user.setLastName(registerDto.getLastname());
        user.setUsername(registerDto.getUsername());
        user.setEmail(registerDto.getEmail());
        user.setPassword(passwordEncoder.encode(registerDto.getPassword()));
        if (registerDto.getColor() != null && !registerDto.getColor().isEmpty()) {
            user.setColor(registerDto.getColor());
        }

        Set<Role> roles = new HashSet<>();
        var userRole = RoleEnum.fromNameOrLabel(registerDto.getRole());

        if (null == userRole) {
            throw new APIException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid role!"
            );
        }

        var role = roleRepository.findByRolename(userRole).orElseThrow(() -> new APIException(
                HttpStatus.BAD_REQUEST,
                "Role not found in database!"
        ));

        roles.add(role);
        user.setRoles(roles);

        return saveWithUserType(user);
    }

    @Override
    public JwtAuthResponse login(LoginDto loginDto) {
        userRepository.findByUsernameOrEmail(loginDto.getUsernameOrEmail(), loginDto.getUsernameOrEmail())
                .orElseThrow(() -> new APIException(
                        HttpStatus.BAD_REQUEST,
                        "Invalid username or email!"
                ));

        Authentication authentication = null;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginDto.getUsernameOrEmail(), loginDto.getPassword())
            );
        } catch (BadCredentialsException badCredentialsException) {
            throw new APIException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid username/email or password!"
            );
        } catch (ExpiredJwtException bce) {
            throw new APIException(
                    HttpStatus.BAD_REQUEST,
                    "Expired JWT Token!"
            );
        } catch (Exception ex) {
            throw new APIException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Login failed!"
            );
        }

        authentication.getAuthorities().forEach(authority -> System.out.println("Authority: " + authority.getAuthority()));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        String token = jwtTokenProvider.generateToken(authentication);
        System.out.println("Generated JWT Token: " + token);

        Date expiresAt = jwtTokenProvider.getExpirationDate(token);
        ResponseCookie cookie = jwtTokenProvider.generateJwtCookie(token);

        ZoneId zone = ZoneId.of(applicationTimeZone);
        Instant expiresAtInstant = expiresAt.toInstant();
        ZonedDateTime expiresAtZoned = expiresAtInstant.atZone(zone);
        System.out.println("Token expires at: " + expiresAtZoned);

        String role = authentication.getAuthorities().iterator().next().getAuthority();
        var detailsUser = (org.springframework.security.core.userdetails.User) authentication.getPrincipal();
        String username = detailsUser.getUsername();

        return JwtAuthResponse.builder()
                .tokenType("Bearer")
                .token(token)
                .expiresAt(expiresAtZoned)
                .expireAfterSeconds(expiresAt.getTime() / 1000)
                .role(role)
                .username(username)
                .cookie(cookie.toString())
                .build();
    }

    @Override
    public JwtAuthResponse logout() {
        var cookie = ResponseCookie.from(cookieName, "")
                .httpOnly(true) // Prevent XSS
                .secure(false)   // Send only over HTTPS
                .path("/")      // Accessible across all paths
                .maxAge(0) // Cookie expiration (seconds)
                .sameSite("Lax") // CSRF protection
                .build();

        return JwtAuthResponse.builder().cookie(cookie.toString()).build();
    }

    private User saveWithUserType(User user) {
        RoleEnum userRole = user.getRoles().iterator().next().getRolename();

        Class<? extends User> userTypeClass = USER_TYPE_CLASS_BY_ROLE.get(userRole);
        if (userTypeClass == null) {
            throw new IllegalStateException("Unexpected value: " + userRole);
        }

        User typedUser = BeanUtils.instantiateClass(userTypeClass);
        BeanUtils.copyProperties(user, typedUser);
        return userRepository.save(typedUser);
    }
}
