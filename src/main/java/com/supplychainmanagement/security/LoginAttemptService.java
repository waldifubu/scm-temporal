package com.supplychainmanagement.security;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long LOCK_TIME_DURATION = 15; // 15 minutes

    /**
     * Cache to store the number of failed login attempts for each user. The key is the username, and the value is the number of failed attempts.
     */
    private final Map<String, Integer> attemptsCache = new HashMap<>();

    /**
     * Cache to store the lock time for each user. The key is the username, and the value is the time when the user was locked out.
     */
    private final Map<String, LocalDateTime> lockCache = new HashMap<>();

    /**
     * Increments the failed login attempt count for the given username. If the number of failed attempts reaches the maximum allowed attempts,
     * the user is locked out for a specified duration.
     */
    public void loginFailed(String username) {
        attemptsCache.put(username, attemptsCache.getOrDefault(username, 0) + 1);
        if (attemptsCache.get(username) >= MAX_ATTEMPTS) {
            lockCache.put(username, LocalDateTime.now());
        }
    }

    /**
     * Resets the failed login attempt count and removes any lock for the given username. This method should be called when a user successfully logs in.
     */
    public void loginSucceeded(String username) {
        attemptsCache.remove(username);
        lockCache.remove(username);
    }

    /**
     * Checks if the given username is currently locked out due to too many failed login attempts. If the lock duration has expired, the lock is removed.
     */
    public boolean isLocked(String username) {
        if (!lockCache.containsKey(username)) {
            return false;
        }

        LocalDateTime lockTime = lockCache.get(username);
        if (lockTime.plusMinutes(LOCK_TIME_DURATION).isBefore(LocalDateTime.now())) {
            lockCache.remove(username);
            attemptsCache.remove(username);
            return false;
        }

        return true;
    }

    /**
     * Returns the remaining lock time in minutes for the given username. If the user is not locked, it returns 0.
     */
    public long getRemainingLockTime(String username) {
        if (!isLocked(username)) {
            return 0;
        }
        LocalDateTime lockTime = lockCache.get(username);
        LocalDateTime unlockTime = lockTime.plusMinutes(LOCK_TIME_DURATION);
        return java.time.Duration.between(LocalDateTime.now(), unlockTime).toMinutes();
    }
}