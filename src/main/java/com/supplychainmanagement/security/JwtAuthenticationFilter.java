package com.supplychainmanagement.security;

import com.supplychainmanagement.exception.APIException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Arrays;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider; // You can implement this class to handle JWT token operations

    private final UserDetailsService userDetailsService; // You can use this to load user details based on the token

    private final HandlerExceptionResolver handlerExceptionResolver;

    @Value("${app.jwtCookieName}")
    private String cookieName;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, UserDetailsService userDetailsService,
                                   @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userDetailsService = userDetailsService;
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    /*
    @Override
    protected void doFilterInternal2(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        String jwtToken = getTokenFromRequest(request);

        // Check if the Authorization header is present and starts with "Bearer "
        if (StringUtils.hasText(jwtToken) && jwtTokenProvider.validateToken(jwtToken)) { // Validate the token (you can implement this method in JwtTokenProvider)
            String username = jwtTokenProvider.getUsername(jwtToken); // Extract the username from the token (you can implement this method in JwtTokenProvider)
            UserDetails userDetails = userDetailsService.loadUserByUsername(username); // Load user details based on the username

            // Create an authentication token with the user details and authorities
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            // Set the authentication details
            authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authenticationToken); // Set the authentication in the security context
        }

        // Continue with the filter chain
        filterChain.doFilter(request, response);
    }
     */


    // Auth endpoints and CORS preflights must pass without a token,
    // non-/api routes (Thymeleaf web app) are not JWT-secured at all.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || path.startsWith("/api/auth/")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = getTokenFromCookie(request);
        if (token == null) {
            token = getTokenFromRequest(request);
        }

        try {
            authenticate(request, token);
        } catch (Exception ex) {
            // A servlet filter runs before the DispatcherServlet, so throwing here would
            // bypass the GlobalExceptionHandler — delegate to it explicitly instead.
            SecurityContextHolder.clearContext();
            handlerExceptionResolver.resolveException(request, response, null, ex);
            return;
        }

        // Continue with the filter chain
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        if (!StringUtils.hasText(token)) {
            throw new BadCredentialsException("Missing JWT token");
        }

        String username;
        try {
            username = jwtTokenProvider.getUsername(token); // parses and verifies the token, throws on expired/invalid
        } catch (ExpiredJwtException ex) {
            logger.warn(ex.getMessage());
            throw new BadCredentialsException("JWT token expired", ex);
        } catch (IllegalArgumentException ex) {
            logger.warn(ex.getMessage());
            throw new BadCredentialsException("Invalid JWT token", ex);
        } catch (SignatureException ex) {
            logger.warn(ex.getMessage());
            throw new APIException(HttpStatus.UNAUTHORIZED, "Signature Error");
        } catch (MalformedJwtException ex) {
            logger.warn(ex.getMessage());
            throw new APIException(HttpStatus.BAD_REQUEST, "Invalid JWT token");
        } catch (UnsupportedJwtException ex) {
            logger.warn(ex.getMessage());
            throw new APIException(HttpStatus.UNAUTHORIZED, "Unsupported JWT token");
        }

        UserDetails userDetails;
        try {
            userDetails = userDetailsService.loadUserByUsername(username); // Load user details based on the username
        } catch (UsernameNotFoundException ex) {
            throw new BadCredentialsException(ex.getMessage(), ex);
        }

        // Create an authentication token with the user details and authorities
        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        // Set the authentication details
        authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authenticationToken); // Set the auth
    }

    public String getTokenFromCookie(HttpServletRequest request) {
        String token = null;
        // Extract JWT from cookie
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            token = Arrays.stream(cookies).filter(cookie -> cookieName.equals(cookie.getName())).findFirst().map(Cookie::getValue).orElse(null);
        }

        return token;
    }

    public String getTokenFromRequest(HttpServletRequest request) {
        // Get the JWT token from the request header
        String authorizationHeader = request.getHeader("Authorization");

        if (StringUtils.hasText(authorizationHeader) && authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7); // Extract the token
        }

        return null;
    }
}
