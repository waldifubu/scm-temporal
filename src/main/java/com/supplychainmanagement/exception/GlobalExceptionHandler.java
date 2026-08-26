package com.supplychainmanagement.exception;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.exc.InvalidFormatException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

// Deliberately unrestricted: errors delegated from the JwtAuthenticationFilter carry
// no handler type, so an annotations-restricted advice would never match them.
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorDetails> handleUserNotFoundException(ResourceNotFoundException ex, WebRequest webRequest) {
        ErrorDetails errorDetails = buildErrorDetails(ex, webRequest, "RESOURCE_NOT_FOUND");
        return new ResponseEntity<>(errorDetails, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorDetails> handleEmailExistsException(EmailAlreadyExistsException ex, WebRequest webRequest) {
        ErrorDetails errorDetails = buildErrorDetails(ex, webRequest, "EMAIL_ALREADY_EXISTS");
        return new ResponseEntity<>(errorDetails, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(APIException.class)
    public ResponseEntity<ErrorDetails> handleAPIException(APIException ex, WebRequest webRequest) {
        ErrorDetails errorDetails = buildErrorDetails(ex, webRequest, "API_ERROR");
        return new ResponseEntity<>(errorDetails, ex.getStatus());
    }

    @ExceptionHandler(UnsufficientException.class)
    public ResponseEntity<ErrorDetails> handleUnsufficientException(UnsufficientException ex, WebRequest webRequest) {
        ErrorDetails errorDetails = buildErrorDetails(ex, webRequest, "UNSUFFICIENT_AMOUNT");
        return new ResponseEntity<>(errorDetails, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(AccountException.class)
    public ResponseEntity<ErrorDetails> handleAccountException(AccountException ex, WebRequest webRequest) {
        ErrorDetails errorDetails = buildErrorDetails(ex, webRequest, "ACCOUNT_NOT_FOUND");
        return new ResponseEntity<>(errorDetails, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorDetails> handleBadCredentials(BadCredentialsException ex, WebRequest webRequest) {
        ErrorDetails errorDetails = buildErrorDetails(ex, webRequest, "BAD_CREDENTIALS");
        return new ResponseEntity<>(errorDetails, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(WrongRoleException.class)
    public ResponseEntity<ErrorDetails> handleWrongRole(WrongRoleException ex, WebRequest webRequest) {
        ErrorDetails errorDetails = buildErrorDetails(ex, webRequest, "WRONG_ROLE");
        return new ResponseEntity<>(errorDetails, HttpStatus.FORBIDDEN);
    }

    /**
     * On denial, {@code @PreAuthorize} throws an {@code AuthorizationDeniedException}
     * (a subclass of {@link AccessDeniedException}) out of the controller method. It therefore
     * never reaches Spring Security's ExceptionTranslationFilter and has to be resolved here in
     * the MVC layer - without this handler it falls through to the catch-all below and turns into
     * a 500 carrying the message "Access Denied".
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorDetails> handleAccessDenied(AccessDeniedException ex, WebRequest webRequest) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // Unauthenticated means 401, not 403: the caller does not have the wrong role, they have
        // no role at all. Without this distinction a missing or expired token would get the same
        // response as a token with insufficient privileges.
        if (!isAuthenticated(authentication)) {
            ErrorDetails errorDetails = buildPocessedErrorDetails(
                    "Authentication is required to access this resource", webRequest, "UNAUTHENTICATED");
            return new ResponseEntity<>(errorDetails, HttpStatus.UNAUTHORIZED);
        }

        // Deliberately keep the concrete @PreAuthorize expression away from the client - it belongs
        // in the server log (buildErrorDetails logs it via ex.getMessage()).
        logger.warn("Authorization denied for principal '" + authentication.getName() + "': " + ex.getMessage());

        ErrorDetails errorDetails = buildErrorDetails(
                WrongRoleException.withoutKnownRequirement(), webRequest, "WRONG_ROLE");
        return new ResponseEntity<>(errorDetails, HttpStatus.FORBIDDEN);
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        // Create a map to hold field-specific error messages
        Map<String, String> errorMap = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> errorMap.put(error.getField(), error.getDefaultMessage()));

        return new ResponseEntity<>(errorMap, HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        String message = ex.getMessage();

        if (ex.getCause() instanceof InvalidFormatException ifx
                && ifx.getTargetType() != null && ifx.getTargetType().isEnum() && !ifx.getPath().isEmpty()) {
            message = String.format("Invalid enum value: '%s' for the field: '%s'. The value must be one of: %s.",
                    ifx.getValue(), ifx.getPath().getLast().getPropertyName(), Arrays.toString(ifx.getTargetType().getEnumConstants()));
        }

        ErrorDetails errorDetails = buildPocessedErrorDetails(message, request, "NOT_READABLE_EXCEPTION");
        return new ResponseEntity<>(errorDetails, HttpStatus.BAD_REQUEST);
    }

    /** Mother of exceptions **/
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDetails> handleGenericException(Exception ex, WebRequest webRequest) {
        ErrorDetails errorDetails = buildErrorDetails(ex, webRequest, "INTERNAL_SERVER_ERROR");

        return new ResponseEntity<>(errorDetails, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public ErrorDetails buildErrorDetails(Exception ex, WebRequest webRequest, String errorCode) {
        return buildPocessedErrorDetails(ex.getMessage(), webRequest, errorCode);
    }

    public ErrorDetails buildPocessedErrorDetails(String rawMessage, WebRequest webRequest, String errorCode) {
        String route = HtmlUtils.htmlEscape(webRequest.getDescription(false).replace("uri=", ""));
        String message = HtmlUtils.htmlEscape(rawMessage != null ? rawMessage : "");
        logger.error("Handling Exception: Route: " + route + " | Message: " + message);
        return new ErrorDetails(LocalDateTime.now(), message, route, errorCode);
    }
}