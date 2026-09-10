package com.supplychainmanagement.exception;


public class AccountLockedException extends RuntimeException {

    private final String username;

    public AccountLockedException(String username, long remainingMinutes) {
        super(String.format("Account %s is locked. Please try again in %d minutes", username, remainingMinutes));
        this.username = username;
    }

    public String getUsername() {
        return username;
    }
}


