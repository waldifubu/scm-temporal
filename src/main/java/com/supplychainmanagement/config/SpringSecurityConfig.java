package com.supplychainmanagement.config;

import com.supplychainmanagement.security.JwtAuthenticationEntryPoint;
import com.supplychainmanagement.security.JwtAuthenticationFilter;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@AllArgsConstructor
public class SpringSecurityConfig {

    private UserDetailsService userDetailsService;
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests((authorize) -> {
                    // Role based
/*
                            authorize.requestMatchers(HttpMethod.POST, "/api/**").hasRole("ADMIN");
                            authorize.requestMatchers(HttpMethod.PUT, "/api/**").hasRole("ADMIN");
                            authorize.requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN");
                            authorize.requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("USER", "ADMIN");
                            authorize.requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyRole("USER", "ADMIN");
*/
//                            authorize.requestMatchers("/api/employees").permitAll();
                    authorize.requestMatchers("/api/auth/**").permitAll();
                    authorize.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
//                    authorize.anyRequest().authenticated();
                    authorize.anyRequest().permitAll();
                })
//                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(exception -> exception.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                ;
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
        // Klassische Server-Side-Web-App (Thymeleaf): alle Routen offen,
        // CSRF-Schutz bleibt aktiv (Standard) — Thymeleaf fügt das Token bei th:action automatisch ein.
        http.authorizeHttpRequests((authorize) -> {
                    authorize.requestMatchers("/", "/login", "/register", "/css/**", "/js/**", "/images/**").permitAll();
                    authorize.requestMatchers("/actuator", "/actuator/health", "/actuator/info").permitAll();
//                    authorize.requestMatchers("/actuator/**").hasRole("ADMIN");
                    authorize.anyRequest().permitAll();
                })
                .httpBasic(Customizer.withDefaults())
                .formLogin(form -> form.loginPage("/login")
                        .loginProcessingUrl("/login").defaultSuccessUrl("/users"))
                .logout(logout -> logout.logoutSuccessUrl("/"));

        return http.build();
    }
}
