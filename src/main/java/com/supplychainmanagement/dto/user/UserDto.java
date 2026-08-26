package com.supplychainmanagement.dto.user;

import java.util.Set;

/**
 * Outward-facing view of a user.
 * <p>
 * Deliberately not the {@code User} entity: that one carries the password hash as well as
 * {@code lastLogin}, {@code createdAt}, {@code updatedAt}, {@code userType} and the full role
 * entities - none of which belong in an API response.
 */
public record UserDto(
        Long id,
        String firstName,
        String lastName,
        String username,
        String email,
        String color,
        Boolean isActive,
        Set<String> roles
) {
}
