package com.supplychainmanagement.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import io.jsonwebtoken.security.WeakKeyException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider; // You can implement this class to handle JWT token operations

    private final UserDetailsService userDetailsService; // You can use this to load user details based on the token

    @Value("${app.jwtCookieName}")
    private String cookieName;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, UserDetailsService userDetailsService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userDetailsService = userDetailsService;
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


    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = getTokenFromCookie(request);

        try {
            if (token == null) {
                filterChain.doFilter(request, response);
                return;
            }

            if (!jwtTokenProvider.validateToken(token)) {
                filterChain.doFilter(request, response);
                return;
            }

            String username = jwtTokenProvider.getUsername(token); // Extract the username from the token (you can implement this method in JwtTokenProvider)
            UserDetails userDetails = userDetailsService.loadUserByUsername(username); // Load user details based on the username

            // Create an authentication token with the user details and authorities
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            // Set the authentication details
            authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authenticationToken); // Set the auth
        } catch (ExpiredJwtException | WeakKeyException ex) {
            final String expiredMsg = ex.getMessage();
            logger.warn(expiredMsg);
            filterChain.doFilter(request, response);
            return;
        } catch (SignatureException ex) {
            final String expiredMsg = ex.getMessage();
            logger.warn(expiredMsg);
            filterChain.doFilter(request, response);
            return;
        } catch (MalformedJwtException ex) {
            logger.warn(ex.getMessage());
            filterChain.doFilter(request, response);
            return;
        }
        System.out.println(jwtTokenProvider.getExpirationDate(token).getTime());

        // Continue with the filter chain
        filterChain.doFilter(request, response);
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
