package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Role;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.RoleRepository;
import com.supplychainmanagement.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Does this user - known only by id - hold one of these roles", read from the stored roles. It is what a
 * service asks when the principal is not at hand, e.g. "may this caller cancel any shipment, or only
 * their own".
 */
class RoleServiceHasAnyRoleTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleServiceImpl roleService = new RoleServiceImpl(userRepository, mock(RoleRepository.class));

    private void userWith(Long id, RoleEnum... roles) {
        Distributor user = new Distributor();
        user.setId(id);
        user.setRoles(Set.of(roles).stream().map(name -> {
            Role role = new Role();
            role.setRolename(name);
            return role;
        }).collect(java.util.stream.Collectors.toSet()));
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
    }

    @Test
    void isTrueForAnyOneOfTheRolesTheUserHolds() {
        userWith(5L, RoleEnum.LOGISTICS);

        assertThat(roleService.hasAnyRole(5L, RoleEnum.ADMIN, RoleEnum.LOGISTICS)).isTrue();
        assertThat(roleService.hasAnyRole(5L, RoleEnum.LOGISTICS)).isTrue();
    }

    @Test
    void isFalseForRolesTheUserDoesNotHold() {
        userWith(5L, RoleEnum.DISTRIBUTOR);

        assertThat(roleService.hasAnyRole(5L, RoleEnum.ADMIN, RoleEnum.LOGISTICS)).isFalse();
    }

    @Test
    void countsOneOfSeveralHeldRoles() {
        userWith(5L, RoleEnum.DISTRIBUTOR, RoleEnum.ADMIN);

        assertThat(roleService.hasAnyRole(5L, RoleEnum.ADMIN, RoleEnum.LOGISTICS)).isTrue();
    }

    @Test
    void isFalseForAnUnknownUserANullIdAndNoRolesAsked() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());
        userWith(5L, RoleEnum.ADMIN);

        assertThat(roleService.hasAnyRole(404L, RoleEnum.ADMIN)).isFalse();
        assertThat(roleService.hasAnyRole(null, RoleEnum.ADMIN)).isFalse();
        assertThat(roleService.hasAnyRole(5L)).isFalse();
        assertThat(roleService.hasAnyRole(5L, (RoleEnum[]) null)).isFalse();
    }

    @Test
    void agreesWithIsAdmin() {
        userWith(5L, RoleEnum.ADMIN);
        userWith(6L, RoleEnum.LOGISTICS);

        assertThat(roleService.hasAnyRole(5L, RoleEnum.ADMIN)).isEqualTo(roleService.isAdmin(5L)).isTrue();
        assertThat(roleService.hasAnyRole(6L, RoleEnum.ADMIN)).isEqualTo(roleService.isAdmin(6L)).isFalse();
    }
}
