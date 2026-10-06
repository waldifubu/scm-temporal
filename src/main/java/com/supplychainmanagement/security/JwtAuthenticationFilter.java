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
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
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
import java.util.regex.Pattern;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider; // You can implement this class to handle JWT token operations

    private final UserDetailsService userDetailsService; // You can use this to load user details based on the token

    private final HandlerExceptionResolver handlerExceptionResolver;

    private static final Pattern PUBLIC_API_PATTERN = Pattern.compile("^/api/(?:v?[0-9\\.]+/)?auth(/.*)?$");

    @Value("${app.cookie.name}")
    private String cookieName;

    /**
     * Where the authentication is written - the strategy of <strong>this application context</strong>,
     * which is the one the authorization filters of the same context read from.
     * <p>
     * It used to be the static {@code SecurityContextHolder.getContext()}, and that is a JVM-wide
     * variable. The Vaadin integration installs a {@code VaadinAwareSecurityContextHolderStrategy}
     * into it <em>per context</em>, so a second Spring context started in the same JVM replaces it:
     * this filter then wrote into the new holder while the first context's {@code AuthorizationFilter}
     * kept reading from its own - two different stores, and a valid token ended as an unauthenticated
     * 401 with an empty body. Measured, not assumed: two test classes sharing one context passed or
     * failed depending on whether a third class had started another one in between.
     * <p>
     * Production runs one context and never saw it. It still is the wrong way round: Spring Security
     * has recommended a {@code SecurityContextHolderStrategy} bean over the static holder since 5.8,
     * and takes it for all of its own filters through {@code setSecurityContextHolderStrategy}.
     * <p>
     * Without such a bean the default is the static holder, as before.
     */
    private SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    @Autowired(required = false)
    public void setSecurityContextHolderStrategy(SecurityContextHolderStrategy securityContextHolderStrategy) {
        this.securityContextHolderStrategy = securityContextHolderStrategy;
    }

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
                || PUBLIC_API_PATTERN.matcher(path).matches()
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String token = getTokenFromCookie(request);
        if (token == null) {
            token = getTokenFromRequestHeader(request);
        }

        try {
            authenticate(request, token);
        } catch (Exception ex) {
            // A servlet filter runs before the DispatcherServlet, so throwing here would
            // bypass the GlobalExceptionHandler — delegate to it explicitly instead.
            securityContextHolderStrategy.clearContext();
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
            // 401, not 410. Every other way a token can fail is a 401 here, and a client has exactly one
            // rule for it - log in again. 410 means "this resource used to exist and is gone for good",
            // which a session is not; a client following the rule would treat an expired session as a
            // different kind of error from an invalid one, though the remedy is identical.
            throw new APIException(HttpStatus.UNAUTHORIZED, "JWT token expired: "+ex.getMessage());
        } catch (IllegalArgumentException ex) {
            logger.warn(ex.getMessage());
            throw new BadCredentialsException("Invalid JWT token", ex);
        } catch (SignatureException ex) {
            logger.warn(ex.getMessage());
            throw new APIException(HttpStatus.UNAUTHORIZED, "Signature Error");
        } catch (MalformedJwtException ex) {
            logger.warn(ex.getMessage());
            // 401 like the other token failures. It was the one 400 among them: a token that cannot be
            // parsed is not a malformed request, it is credentials that do not authenticate.
            throw new APIException(HttpStatus.UNAUTHORIZED, "Invalid JWT token");
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

        securityContextHolderStrategy.getContext().setAuthentication(authenticationToken); // Set the auth
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

    public String getTokenFromRequestHeader(HttpServletRequest request) {
        // Get the JWT token from the request header
        String authorizationHeader = request.getHeader("Authorization");

        if (StringUtils.hasText(authorizationHeader) && authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7); // Extract the token
        }

        return null;
    }
}
