package com.supplychainmanagement.service;

import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;

public interface RoleService {

    boolean isAdmin(org.springframework.security.core.userdetails.User authUser);

    boolean isPrivilegedUser(org.springframework.security.core.userdetails.User authUser);

    /**
     * Checks whether the caller holds at least one of the given roles.
     * A {@code null} principal (unauthenticated) yields {@code false} instead of an NPE.
     */
    boolean hasAnyAuthority(org.springframework.security.core.userdetails.User authUser, RoleEnum... roles);

    /**
     * Like {@link #hasAnyAuthority}, but throws a
     * {@link com.supplychainmanagement.exception.WrongRoleException} (HTTP 403) when the authority
     * is missing.
     * <p>
     * For endpoints, prefer {@code @PreAuthorize} - the rule then lives in exactly one place. This
     * method is meant for checks an annotation cannot express, for instance when the required role
     * only follows from the payload.
     */
    void requireAnyAuthority(org.springframework.security.core.userdetails.User authUser, RoleEnum... roles);

    void convertToAdmin(User user);
}
