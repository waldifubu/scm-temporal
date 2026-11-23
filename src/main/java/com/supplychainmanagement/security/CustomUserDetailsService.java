package com.supplychainmanagement.security;


import com.supplychainmanagement.entity.Role;
import com.supplychainmanagement.repository.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private UserRepository userRepository;

    private static SimpleGrantedAuthority apply(Role role) {
        return new SimpleGrantedAuthority(role.getRolename().name());
    }

    @Override
    public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {
        var user = userRepository.findByUsernameOrEmail(usernameOrEmail, usernameOrEmail).orElseThrow(
                () -> new UsernameNotFoundException("User not found with username or email: " + usernameOrEmail)
        );

        Set<GrantedAuthority> authorities = user.getRoles().stream()
                .map(CustomUserDetailsService::apply)
                .collect(Collectors.toSet());
        // A missing flag counts as not active, like everywhere else. The filter reads isEnabled() on every
        // request, so deactivating an account also ends the access tokens it already holds.
        boolean enabled = Boolean.TRUE.equals(user.getIsActive());
        return new User(usernameOrEmail, user.getPassword(), enabled, true, true, true, authorities);
    }
}
