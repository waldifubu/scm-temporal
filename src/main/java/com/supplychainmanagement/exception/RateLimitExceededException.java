package com.supplychainmanagement.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown by {@code RateLimitingFilter} when a bucket4j bucket has no tokens left.
 * <p>
 * Mapped by {@link GlobalExceptionHandler} to HTTP 429 with the error code
 * {@code RATE_LIMIT_EXCEEDED}, which also sets the {@code Retry-After} header from
 * {@link #getRetryAfterSeconds()}.
 */
public class RateLimitExceededException extends APIException {

    private final long retryAfterSeconds;

    /**
     * @param retryAfterSeconds the wait as bucket4j reports it; converted to seconds right
     *                          here, so milliseconds never leave this exception
     */
    public RateLimitExceededException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded. Please try again in " + retryAfterSeconds + " seconds");
        this.retryAfterSeconds = retryAfterSeconds;
    }


    /**
     * The wait in whole seconds - the unit RFC 9110 defines for {@code Retry-After}.
     */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}