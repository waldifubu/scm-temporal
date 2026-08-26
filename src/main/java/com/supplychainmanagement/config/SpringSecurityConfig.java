package com.supplychainmanagement.config;

import com.supplychainmanagement.security.JwtAuthenticationEntryPoint;
import com.supplychainmanagement.security.JwtAuthenticationFilter;
import com.supplychainmanagement.vaadin.views.LoginView;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import jakarta.servlet.DispatcherType;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
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
                    // IMPORTANT: controller methods returning Mono/Flux trigger a second, internal
                    // ASYNC dispatch to complete the response. JwtAuthenticationFilter
                    // (OncePerRequestFilter) skips itself on that second pass by default, so the request
                    // arrives there "anonymous". Without ASYNC/ERROR listed here, the AuthorizationFilter
                    // fallback rule would demand authentication for it and fail with
                    // "Full authentication is required to access this resource" - even though the
                    // original request was authenticated successfully.
                    //
                    // Permit ASYNC and ERROR ONLY: the actual call arrives as REQUEST and MUST go through
                    // the rules below. With REQUEST (or FORWARD) in this list, the rule would match every
                    // single call - it is evaluated first and the first match wins - rendering everything
                    // after it, including anyRequest().authenticated(), dead code. Authorization happens
                    // on the REQUEST dispatch; the ASYNC pass merely delivers the finished response.
                    authorize.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll();
                    // Role based
/*
                            authorize.requestMatchers(HttpMethod.POST, "/api/**").hasRole("ADMIN");
                            authorize.requestMatchers(HttpMethod.PUT, "/api/**").hasRole("ADMIN");
                            authorize.requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN");
                            authorize.requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("USER", "ADMIN");
                            authorize.requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyRole("USER", "ADMIN");

                                authorize.requestMatchers("/api/admin/**").hasRole("ADMIN")
                authorize.requestMatchers("/api/manager/**").hasAnyRole("MANAGER", "ADMIN")
                authorize.requestMatchers("/api/supplier/**").hasAnyRole("SUPPLIER", "ADMIN")
                authorize.requestMatchers("/api/distributor/**").hasAnyRole("DISTRIBUTOR", "ADMIN")
                authorize.requestMatchers("/api/customer/**").hasAnyRole("CUSTOMER", "ADMIN")
                authorize.requestMatchers("/api/warehouse/**").hasAnyRole("WAREHOUSE", "ADMIN")
*/
//                            authorize.requestMatchers("/api/employees").permitAll();
                    authorize.requestMatchers("/api/auth/**").permitAll();
                    authorize.requestMatchers("/api/{version:[0-9]+\\.[0-9]+}/auth/**").permitAll();
                    authorize.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    authorize.anyRequest().authenticated();
//                    authorize.anyRequest().permitAll();
                })
                // Make sure the session creation policy is stateless (required for JWT)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
//                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(exception -> exception.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

        ;
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain vaadinFilterChain(HttpSecurity http) throws Exception {
        // Dedicated chain for Vaadin (vaadin.url-mapping=/app/*), mirroring apiFilterChain.
        // Reason: HttpSecurity#authorizeHttpRequests runs its customizer IMMEDIATELY, while
        // VaadinSecurityConfigurer registers its own requestMatchers rules lazily in init()
        // during http.build() - which therefore always happens AFTER our own anyRequest() call,
        // no matter how the calls are ordered in the source. In a shared chain that leads to
        // "Can't configure requestMatchers after anyRequest". With securityMatcher, Vaadin gets
        // its own isolated registry and also takes care of CSRF for its UIDL protocol itself.
        http.securityMatcher("/app/**");
        http.with(VaadinSecurityConfigurer.vaadin(), configurer -> {
            configurer.loginView(LoginView.class).defaultSuccessUrl("/app/products");
        });
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
        // Classic server-side web app (Thymeleaf): all routes open, CSRF protection stays on
        // (the default) - Thymeleaf inserts the token automatically on th:action.
        http.authorizeHttpRequests((authorize) -> {
                    authorize.requestMatchers("/", "/login", "/register", "/css/**", "/js/**", "/images/**").permitAll();
                    authorize.requestMatchers("/actuator", "/actuator/health", "/actuator/info").permitAll();
//                    authorize.requestMatchers("/actuator/**").hasRole("ADMIN");
                    authorize.anyRequest().permitAll();
                })
//                .httpBasic(Customizer.withDefaults())
                .formLogin(form -> form.loginPage("/login")
                        .loginProcessingUrl("/login").defaultSuccessUrl("/"))
                .logout(logout -> logout.logoutSuccessUrl("/"));

        return http.build();
    }
}
