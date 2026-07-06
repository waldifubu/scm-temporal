package com.example.supplychainmanagement.security.impl;

import com.example.supplychainmanagement.dto.auth.JwtAuthResponse;
import com.example.supplychainmanagement.dto.auth.LoginDto;
import com.example.supplychainmanagement.dto.auth.RegisterDto;
import com.example.supplychainmanagement.entity.Role;
import com.example.supplychainmanagement.entity.users.*;
import com.example.supplychainmanagement.exception.APIException;
import com.example.supplychainmanagement.model.enums.RoleEnum;
import com.example.supplychainmanagement.repository.RoleRepository;
import com.example.supplychainmanagement.repository.UserRepository;
import com.example.supplychainmanagement.security.AuthService;
import com.example.supplychainmanagement.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
        if(registerDto.getColor() != null && !registerDto.getColor().isEmpty()){
            user.setColor(registerDto.getColor());
        }

        Set<Role> roles = new HashSet<>();
        var userRole = RoleEnum.fromNameOrLabel(registerDto.getRole());

        if(null == userRole) {
            throw new APIException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid role!"
            );
        }

        var role = roleRepository.findByName(userRole.name()).orElseThrow(() -> new APIException(
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
        }

        authentication.getAuthorities().forEach(authority -> System.out.println("Authority: " + authority.getAuthority()));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        String token = jwtTokenProvider.generateToken(authentication);
        System.out.println("Generated JWT Token: " + token);

        Date expiresAt = jwtTokenProvider.getExpirationDate(token);

        ZoneId zone = ZoneId.of(applicationTimeZone);
        Instant expiresAtInstant = expiresAt.toInstant();
        ZonedDateTime expiresAtZoned = expiresAtInstant.atZone(zone);
        System.out.println("Token expires at: " + expiresAtZoned);

        String role = authentication.getAuthorities().iterator().next().getAuthority();

        return new JwtAuthResponse(token, "Bearer", expiresAtZoned, expiresAt.getTime() / 1000, role);
    }

    private User saveWithUserType(User user) {
        String persistedRoleName = user.getRoles().iterator().next().getName();
        RoleEnum userRole = RoleEnum.fromNameOrLabel(persistedRoleName);

        Class<? extends User> userTypeClass = USER_TYPE_CLASS_BY_ROLE.get(userRole);
        if (userTypeClass == null) {
            throw new IllegalStateException("Unexpected value: " + userRole);
        }

        User typedUser = BeanUtils.instantiateClass(userTypeClass);
        BeanUtils.copyProperties(user, typedUser);
        return userRepository.save(typedUser);
    }
}
