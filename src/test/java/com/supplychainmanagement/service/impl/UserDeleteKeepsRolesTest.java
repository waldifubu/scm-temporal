package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.RoleRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.UserService;
import com.supplychainmanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting a user removes the link to the roles (the rows of {@code users_roles}), never the roles themselves
 * and never another user's link to them.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class UserDeleteKeepsRolesTest {

    @Autowired
    private TestData testData;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntityManager entityManager;

    private long linkRows(Long userId) {
        return ((Number) entityManager
                .createNativeQuery("select count(*) from users_roles where user_id = ?1")
                .setParameter(1, userId).getSingleResult()).longValue();
    }

    @Test
    void deletesTheUsersRoleLinksButNotTheRoles() {
        User doomed = testData.userWithRoles(RoleEnum.WAREHOUSE, RoleEnum.LOGISTICS);
        User other = testData.userWithRoles(RoleEnum.WAREHOUSE);
        entityManager.flush();
        long rolesBefore = roleRepository.count();
        assertThat(linkRows(doomed.getId())).isEqualTo(2);

        userService.deleteById(doomed.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.findById(doomed.getId())).isEmpty();
        assertThat(linkRows(doomed.getId())).isZero();
        assertThat(roleRepository.count()).isEqualTo(rolesBefore);
        assertThat(roleRepository.findByRolename(RoleEnum.WAREHOUSE)).isPresent();
        assertThat(roleRepository.findByRolename(RoleEnum.LOGISTICS)).isPresent();
        // The other holder of WAREHOUSE keeps it.
        assertThat(linkRows(other.getId())).isEqualTo(1);
    }
}
