package com.supplychainmanagement.security.impl;

import com.supplychainmanagement.dto.auth.AuthDtos.AuthResponse;
import com.supplychainmanagement.dto.auth.AuthDtos.LoginRequest;
import com.supplychainmanagement.dto.auth.AuthDtos.RegisterRequest;
import com.supplychainmanagement.entity.Role;
import com.supplychainmanagement.entity.users.*;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.AccountLockedException;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.RoleRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.security.AuthService;
import com.supplychainmanagement.security.JwtTokenProvider;
import com.supplychainmanagement.security.LoginAttemptService;
import com.supplychainmanagement.security.RefreshTokenService;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /**
     * What a login answers when it fails on the credentials - an unknown user and a wrong password
     * alike, with the same status and the same words.
     * <p>
     * They used to differ: an unknown user got "Invalid username or email!" and a wrong password
     * "Invalid username/email or password!", both as 400. Two messages tell anybody which user names
     * exist, and the status was wrong as well - 400 says the request was malformed, 401 says the
     * credentials were not accepted, which is what a client has to be able to tell apart from a real
     * bad request. One text keeps the answer from saying more than "no".
     */
    static final String INVALID_CREDENTIALS = "Invalid username/email or password!";

    private static final Map<RoleEnum, Class<? extends User>> USER_TYPE_CLASS_BY_ROLE = Map.of(
            RoleEnum.CUSTOMER, Customer.class,
            RoleEnum.MANAGER, Manager.class,
            RoleEnum.SUPPLIER, Supplier.class,
            RoleEnum.WAREHOUSE, Warehouse.class,
            RoleEnum.LOGISTICS, Logistics.class,
            RoleEnum.DISTRIBUTOR, Distributor.class,
            RoleEnum.ADMIN, Admin.class
    );

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final LoginAttemptService loginAttemptService;
    private final RefreshTokenService refreshTokenService;

    @Value("${application.timezone:UTC}")
    private String applicationTimeZone;

    @Override
    public User register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new APIException(
                    HttpStatus.BAD_REQUEST,
                    "Username already taken!"
            );
        }

        if (userRepository.existsByEmail(request.email())) {
            throw new APIException(
                    HttpStatus.BAD_REQUEST,
                    "Email already taken!"
            );
        }

        var user = new User();
        user.setFirstName(request.firstname());
        user.setLastName(request.lastname());
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        if (request.color() != null && !request.color().isEmpty()) {
            user.setColor(request.color());
        }

        Set<Role> roles = new HashSet<>();
        var userRole = RoleEnum.valueOfLabel(request.role());

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
    public AuthResponse login(LoginRequest request) {
        var dbUser = userRepository.findByUsernameOrEmail(request.usernameOrEmail(), request.usernameOrEmail())
                .orElseThrow(() -> new APIException(
                        HttpStatus.UNAUTHORIZED,
                        INVALID_CREDENTIALS
                ));

        if(!dbUser.getIsActive()) {
            throw new APIException(
                    HttpStatus.FORBIDDEN,
                    "Account is disabled!"
            );
        }

        String dbUserUsername = dbUser.getUsername();
        if (loginAttemptService.isLocked(dbUserUsername)) {
            long remainingMinutes = loginAttemptService.getRemainingLockTime(dbUserUsername);
            throw new AccountLockedException(dbUserUsername, remainingMinutes);
        }

        Authentication authentication = null;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.usernameOrEmail(), request.password())
            );
        } catch (BadCredentialsException badCredentialsException) {
            loginAttemptService.loginFailed(dbUserUsername);
            throw new APIException(
                    HttpStatus.UNAUTHORIZED,
                    INVALID_CREDENTIALS
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

        String role = authentication.getAuthorities().iterator().next().getAuthority().toLowerCase(Locale.ROOT);
        var detailsUser = (org.springframework.security.core.userdetails.User) authentication.getPrincipal();

        assert detailsUser != null;
        String username = detailsUser.getUsername();

        ZoneId zone = ZoneId.of(applicationTimeZone);
        Long lastLogin = dbUser.getLastLogin() != null
                ? dbUser.getLastLogin().atZone(zone).toInstant().toEpochMilli()
                : null;

        dbUser.setLastLogin(LocalDateTime.now());
        userRepository.save(dbUser);
        loginAttemptService.loginSucceeded(dbUserUsername);

        // The refresh token starts a session of its own: a family that ends at logout, at a password change,
        // or when one of its tokens is shown twice.
        String refreshToken = refreshTokenService.startSession(dbUser);

        return answer(token, username, role, lastLogin, refreshTokenService.cookie(refreshToken));
    }

    // Deliberately not @Transactional: rotate() commits its own work, including a family revocation that has to
    // survive the 401 it is followed by. A transaction around it here would roll that revocation back.
    @Override
    public AuthResponse refresh(String refreshToken) {
        RefreshTokenService.Issued issued = refreshTokenService.rotate(refreshToken);

        // Roles as the database has them now - a refresh is exactly the moment a changed role should reach
        // the access token.
        List<GrantedAuthority> authorities = issued.authorities().stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        String token = jwtTokenProvider.generateToken(issued.username(), authorities);
        String role = authorities.isEmpty() ? null : authorities.getFirst().getAuthority().toLowerCase(Locale.ROOT);
        ZoneId zone = ZoneId.of(applicationTimeZone);
        Long lastLogin = issued.lastLogin() != null
                ? issued.lastLogin().atZone(zone).toInstant().toEpochMilli()
                : null;

        return answer(token, issued.username(), role, lastLogin, refreshTokenService.cookie(issued.token()));
    }

    /** The answer of a login or a refresh: the access token cookie, the refresh cookie, and when the first ends. */
    private AuthResponse answer(String token, String username, String role, Long lastLogin,
                                ResponseCookie refreshCookie) {
        Date expiresAt = jwtTokenProvider.getExpirationDate(token);
        ZonedDateTime expiresAtZoned = expiresAt.toInstant().atZone(ZoneId.of(applicationTimeZone));

        return AuthResponse.builder()
                .tokenType("Bearer")
                .expiresAt(expiresAtZoned)
                .expireAfterSeconds(expiresAt.getTime() / 1000)
                .role(role)
                .lastLogin(lastLogin)
                .username(username)
                .cookie(jwtTokenProvider.generateJwtCookie(token).toString())
                .refreshCookie(refreshCookie.toString())
                .build();
    }

    @Override
    public AuthResponse logout() {
        return logout(null);
    }

    @Override
    public AuthResponse logout(String refreshToken) {
        refreshTokenService.revokeFamilyOf(refreshToken);
        return AuthResponse.builder()
                .cookie(jwtTokenProvider.removeJwtCookie().toString())
                .refreshCookie(refreshTokenService.removalCookie().toString())
                .build();
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
