package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Role;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.RoleRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.RoleService;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

@Log4j2
@AllArgsConstructor
@Service
public class RoleServiceImpl implements RoleService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Override
    public boolean isAdmin(User authUser) {
        return authUser.getAuthorities().stream()
                .anyMatch(authority -> Objects.equals(authority.getAuthority(), RoleEnum.ROLE_ADMIN.name()));
    }

    @Override
    public boolean isPrivilegedUser(User authUser) {
        Set<String> privilegedRoles = Set.of(RoleEnum.ROLE_ADMIN.name(), RoleEnum.ROLE_MANAGER.name());
        return authUser.getAuthorities().stream()
                .anyMatch(authority -> privilegedRoles.contains(authority.getAuthority()));
    }

    @Override
    public void convertToAdmin(com.supplychainmanagement.entity.users.User user) {
        Set<Role> roles = user.getRoles();
        var adminRole = roleRepository.findByRolename(RoleEnum.ROLE_ADMIN).orElseThrow(() -> new APIException(
                HttpStatus.BAD_REQUEST,
                "Role not found in database!"
        ));

        roles.add(adminRole);
        user.setRoles(roles);
        log.info(user.getUsername() + " has been converted to Admin");
        userRepository.save(user);
    }
}
