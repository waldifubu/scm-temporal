package com.supplychainmanagement.service;

import com.supplychainmanagement.entity.users.User;

public interface RoleService {

    boolean isAdmin(org.springframework.security.core.userdetails.User authUser);

    boolean isPrivilegedUser(org.springframework.security.core.userdetails.User authUser);

    void convertToAdmin(User user);
}
