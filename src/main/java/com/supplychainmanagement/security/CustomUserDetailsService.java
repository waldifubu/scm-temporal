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
        return new User(usernameOrEmail, user.getPassword(), authorities);
    }
}
