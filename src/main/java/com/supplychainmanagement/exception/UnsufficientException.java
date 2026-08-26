package com.supplychainmanagement.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.BAD_REQUEST)
public class UnsufficientException extends RuntimeException {

    private String message;

    public UnsufficientException(String message, double amount) {
        super(message + ". Requested amount: "+ amount);
    }

    /** For cases covering several items, where a single "requested amount" would not fit. */
    public UnsufficientException(String message) {
        super(message);
    }
}
