package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Role;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.WrongRoleException;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.RoleRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.RoleService;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Log4j2
@AllArgsConstructor
@Service
public class RoleServiceImpl implements RoleService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Override
    public boolean isAdmin(User authUser) {
        return hasAnyAuthority(authUser, RoleEnum.ADMIN);
    }

    @Override
    public boolean isPrivilegedUser(User authUser) {
        return hasAnyAuthority(authUser, RoleEnum.ADMIN, RoleEnum.MANAGER);
    }

    @Override
    public boolean hasAnyAuthority(User authUser, RoleEnum... roles) {
        if (authUser == null || authUser.getAuthorities() == null) {
            return false;
        }

        Set<String> accepted = Arrays.stream(roles).map(RoleEnum::name).collect(Collectors.toSet());
        return authUser.getAuthorities().stream()
                .anyMatch(authority -> accepted.contains(authority.getAuthority()));
    }

    @Override
    public void requireAnyAuthority(User authUser, RoleEnum... roles) {
        if (!hasAnyAuthority(authUser, roles)) {
            throw WrongRoleException.requiring(roles);
        }
    }

    @Override
    public void convertToAdmin(com.supplychainmanagement.entity.users.User user) {
        Set<Role> roles = user.getRoles();
        var adminRole = roleRepository.findByRolename(RoleEnum.ADMIN).orElseThrow(() -> new APIException(
                HttpStatus.BAD_REQUEST,
                "Role not found in database!"
        ));

        roles.add(adminRole);
        user.setRoles(roles);
        user.setUserType(RoleEnum.ADMIN.name());
        log.info("{} has been converted to Admin", user.getUsername());
        userRepository.save(user);
    }
}
