package com.supplychainmanagement.exception;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final ExceptionHandlerMethodResolver resolver =
            new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private WebRequest request() {
        return new ServletWebRequest(new MockHttpServletRequest("GET", "/api/1.0/stock/706a99c3"));
    }

    @Test
    void preAuthorizeDenialRoutesToAccessDeniedHandlerNotToCatchAll() {
        var resolved = resolver.resolveMethod(new AuthorizationDeniedException("Access Denied"));

        assertThat(resolved).isNotNull();
        assertThat(resolved.getName()).isEqualTo("handleAccessDenied");
    }

    @Test
    void wrongRoleWinsOverTheApiExceptionHandler() {
        var resolved = resolver.resolveMethod(WrongRoleException.withoutKnownRequirement());

        assertThat(resolved).isNotNull();
        assertThat(resolved.getName()).isEqualTo("handleWrongRole");
    }

    @Test
    void authenticatedButWrongRoleYields403() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("customer", "n/a",
                        List.of(new SimpleGrantedAuthority("CUSTOMER"))));

        var response = handler.handleAccessDenied(new AuthorizationDeniedException("Access Denied"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("WRONG_ROLE");
        // The @PreAuthorize expression must not leak to the client.
        assertThat(response.getBody().message()).doesNotContain("hasAnyAuthority");
    }

    @Test
    void unauthenticatedYields401() {
        var response = handler.handleAccessDenied(new AccessDeniedException("Access Denied"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void anonymousTokenCountsAsUnauthenticated() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        var response = handler.handleAccessDenied(new AccessDeniedException("Access Denied"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void requiringNamesTheAcceptedRoles() {
        var ex = WrongRoleException.requiring(
                com.supplychainmanagement.model.enums.RoleEnum.ADMIN,
                com.supplychainmanagement.model.enums.RoleEnum.WAREHOUSE);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getRequiredAuthorities()).containsExactly("ADMIN", "WAREHOUSE");
        assertThat(ex.getMessage()).contains("ADMIN", "WAREHOUSE");
    }
}
