package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.users.Admin;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.RoleEnum;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Promoting turns the type to admin and leaves ADMIN as the one role link, even if an ADMIN row existed. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class UserPromoteToAdminTest {

    @Autowired
    private TestData testData;
    @Autowired
    private UserService userService;
    @Autowired
    private EntityManager entityManager;

    private String typeOf(Long id) {
        return (String) entityManager.createNativeQuery("select user_type from users where id = ?1")
                .setParameter(1, id).getSingleResult();
    }

    private java.util.List<Object> rolenamesOf(Long id) {
        return (java.util.List<Object>) entityManager.createNativeQuery(
                        "select r.rolename from users_roles ur join roles r on r.id = ur.role_id where ur.user_id = ?1")
                .setParameter(1, id).getResultList();
    }

    @Test
    void changesTheTypeAndReplacesTheRoles() {
        testData.userWithRoles(RoleEnum.ADMIN); // makes sure the ADMIN role row exists in the test schema
        User user = testData.userWithRoles(RoleEnum.WAREHOUSE, RoleEnum.LOGISTICS);
        entityManager.flush();

        User promoted = userService.promoteToAdmin(user.getId());

        assertThat(promoted).isInstanceOf(Admin.class);
        assertThat(typeOf(user.getId())).isEqualTo("admin");
        assertThat(rolenamesOf(user.getId())).containsExactly("ADMIN");
    }

    @Test
    void doesNotDuplicateAnAdminRoleThatIsAlreadyLinked() {
        User user = testData.userWithRoles(RoleEnum.WAREHOUSE, RoleEnum.ADMIN);
        entityManager.flush();

        userService.promoteToAdmin(user.getId());

        assertThat(rolenamesOf(user.getId())).containsExactly("ADMIN");
    }

    @Test
    void refusesAnAdministrator() {
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        entityManager.flush();

        assertThatThrownBy(() -> userService.promoteToAdmin(admin.getId()))
                .isInstanceOf(APIException.class).hasMessageContaining("already");
    }

    // ------------------------------------------------------------------ demote

    @Test
    void demotesAnAdministratorToOneOrdinaryRole() {
        User other = testData.userWithRoles(RoleEnum.ADMIN);
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        testData.userWithRoles(RoleEnum.WAREHOUSE); // the role row
        entityManager.flush();

        User demoted = userService.demote(admin.getId(), RoleEnum.WAREHOUSE);

        assertThat(demoted).isInstanceOf(com.supplychainmanagement.entity.users.Warehouse.class);
        assertThat(typeOf(admin.getId())).isEqualTo("warehouse");
        assertThat(rolenamesOf(admin.getId())).containsExactly("WAREHOUSE");
        assertThat(typeOf(other.getId())).isEqualTo("admin");
    }

    @Test
    void refusesToDemoteAdminAsTheTargetRole() {
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        testData.userWithRoles(RoleEnum.ADMIN);
        entityManager.flush();

        assertThatThrownBy(() -> userService.demote(admin.getId(), RoleEnum.ADMIN))
                .isInstanceOf(APIException.class);
    }

    @Test
    void refusesToDemoteSomebodyWhoIsNoAdministrator() {
        User user = testData.userWithRoles(RoleEnum.WAREHOUSE);
        entityManager.flush();

        assertThatThrownBy(() -> userService.demote(user.getId(), RoleEnum.CUSTOMER))
                .isInstanceOf(APIException.class).hasMessageContaining("not an administrator");
    }

    @Test
    void keepsTheLastAdministrator() {
        // Every administrator but this one is taken out of the count by deleting them from the test's view.
        entityManager.createNativeQuery("delete from users_roles where user_id in (select id from users where user_type = 'admin')").executeUpdate();
        entityManager.createNativeQuery("update users set user_type = 'customer' where user_type = 'admin'").executeUpdate();
        entityManager.clear();
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        testData.userWithRoles(RoleEnum.CUSTOMER);
        entityManager.flush();

        assertThatThrownBy(() -> userService.demote(admin.getId(), RoleEnum.CUSTOMER))
                .isInstanceOf(APIException.class).hasMessageContaining("last administrator");
    }
}
