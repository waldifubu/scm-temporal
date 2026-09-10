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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.server.ResponseStatusException;

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

    /**
     * The catch-all is the last stop for everything no other handler claimed. An exception that
     * states its own status has to keep it - answering 500 would blame the server for a fault the
     * caller caused, and bury which one it was.
     */
    @Test
    void keepsTheStatusOfAnExceptionThatCarriesOne() {
        var response = handler.handleGenericException(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "no such thing"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("NOT_FOUND");
    }

    /** The second way of stating a status: the annotation, inherited ones included. */
    @Test
    void keepsTheStatusOfAnAnnotatedException() {
        var response = handler.handleGenericException(new AnnotatedConflict(), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("CONFLICT");
    }

    /** And an exception that says nothing about a status really is a server error. */
    @Test
    void fallsBackToFiveHundredForAPlainException() {
        var response = handler.handleGenericException(new IllegalStateException("boom"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("INTERNAL_SERVER_ERROR");
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    private static class AnnotatedConflict extends RuntimeException {
    }
}
