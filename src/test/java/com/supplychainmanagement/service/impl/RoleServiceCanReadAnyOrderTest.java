package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.RoleRepository;
import com.supplychainmanagement.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Which roles may read an order that is not their own - one named rule, because two layers have to
 * agree on it. {@code @PreAuthorize} on {@code GET /orders/{orderNo}} named WAREHOUSE and LOGISTICS
 * while the service asked {@code isPrivilegedUser} (ADMIN and MANAGER), and the two disagreed for
 * every order there is.
 */
class RoleServiceCanReadAnyOrderTest {

    private final RoleServiceImpl roleService =
            new RoleServiceImpl(mock(UserRepository.class), mock(RoleRepository.class));

    private static User principalWith(RoleEnum role) {
        return new User("someone", "x", List.of(new SimpleGrantedAuthority(role.name())));
    }

    /** The four roles that work from an order without being its customer. */
    @ParameterizedTest
    @EnumSource(value = RoleEnum.class, names = {"ADMIN", "MANAGER", "WAREHOUSE", "LOGISTICS"})
    void letsTheRolesThatWorkFromAnOrderReadAny(RoleEnum role) {
        assertThat(roleService.canReadAnyOrder(principalWith(role))).isTrue();
    }

    /**
     * Everybody else reads only their own. EXCLUDE rather than a list of the others, so a role added
     * later is refused here until somebody decides otherwise - the safe direction to be wrong in.
     */
    @ParameterizedTest
    @EnumSource(value = RoleEnum.class, names = {"ADMIN", "MANAGER", "WAREHOUSE", "LOGISTICS"},
            mode = EnumSource.Mode.EXCLUDE)
    void leavesEverybodyElseWithTheirOwnOrders(RoleEnum role) {
        assertThat(roleService.canReadAnyOrder(principalWith(role))).isFalse();
    }

    /** And it is a different question from isPrivilegedUser, which stays ADMIN and MANAGER. */
    @Test
    void isNotTheSameQuestionAsBeingPrivileged() {
        User warehouse = principalWith(RoleEnum.WAREHOUSE);

        assertThat(roleService.canReadAnyOrder(warehouse)).isTrue();
        assertThat(roleService.isPrivilegedUser(warehouse)).isFalse();
    }

    @Test
    void refusesAnAnonymousCaller() {
        assertThat(roleService.canReadAnyOrder(null)).isFalse();
    }
}
