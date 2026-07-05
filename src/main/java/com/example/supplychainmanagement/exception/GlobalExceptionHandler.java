package com.example.supplychainmanagement.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.exc.InvalidFormatException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice(annotations = RestController.class)
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
        return new ResponseEntity<>(errorDetails, HttpStatus.CONFLICT);
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

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        // Create a map to hold field-specific error messages
        Map<String, String> errorMap = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> errorMap.put(error.getField(), error.getDefaultMessage()));

        return new ResponseEntity<>(errorMap, HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getMessage();

        if (ex.getCause() instanceof InvalidFormatException ifx
                && ifx.getTargetType() != null && ifx.getTargetType().isEnum() && !ifx.getPath().isEmpty()) {
            message = String.format("Invalid enum value: '%s' for the field: '%s'. The value must be one of: %s.",
                    ifx.getValue(), ifx.getPath().get(ifx.getPath().size() - 1).getPropertyName(), Arrays.toString(ifx.getTargetType().getEnumConstants()));
        }

        ErrorDetails errorDetails = buildErrorDetails(message, request, "NOT_READABLE_EXCEPTION");
        return new ResponseEntity<>(errorDetails, HttpStatus.BAD_REQUEST);
    }

    /** Mother of exceptions **/
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDetails> handleGenericException(Exception ex, WebRequest webRequest) {
        ErrorDetails errorDetails = buildErrorDetails(ex, webRequest, "INTERNAL_SERVER_ERROR");

        return new ResponseEntity<>(errorDetails, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public ErrorDetails buildErrorDetails(Exception ex, WebRequest webRequest, String errorCode) {
        return buildErrorDetails(ex.getMessage(), webRequest, errorCode);
    }

    public ErrorDetails buildErrorDetails(String rawMessage, WebRequest webRequest, String errorCode) {
        String route = HtmlUtils.htmlEscape(webRequest.getDescription(false).replace("uri=", ""));
        String message = HtmlUtils.htmlEscape(rawMessage != null ? rawMessage : "");
        logger.error("Handling Exception: Route: " + route + " | Message: " + message);
        return new ErrorDetails(LocalDateTime.now(), message, route, errorCode);
    }
}