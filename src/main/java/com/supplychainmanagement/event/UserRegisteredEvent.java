package com.supplychainmanagement.event;

import com.supplychainmanagement.model.enums.RoleEnum;

public record UserRegisteredEvent(
        String username,
        String email,
        String role
) {
}
