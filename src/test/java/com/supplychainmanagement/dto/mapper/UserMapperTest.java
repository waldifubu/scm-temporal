package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.entity.Role;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

    private static final String PASSWORD_HASH = "$argon2id$v=19$m=16384,t=2,p=1$c29tZXNhbHQ$hashvalue";

    private final UserMapper mapper = new UserMapperImpl();

    private User sampleUser() {
        User user = new Customer();
        user.setId(42L);
        user.setFirstName("Ada");
        user.setLastName("Lovelace");
        user.setUsername("ada");
        user.setEmail("ada@example.com");
        user.setColor("#336699");
        user.setIsActive(true);
        user.setPassword(PASSWORD_HASH);
        user.setLastLogin(LocalDateTime.of(2026, 8, 26, 9, 0));
        user.setUserType("customer");

        Role role = new Role();
        role.setRolename(RoleEnum.CUSTOMER);
        user.setRoles(new LinkedHashSet<>(java.util.List.of(role)));

        return user;
    }

    @Test
    void mapsOnlyTheFieldsMeantForTheOutsideWorld() {
        var dto = mapper.mapToDto(sampleUser());

        assertThat(dto.id()).isEqualTo(42L);
        assertThat(dto.firstName()).isEqualTo("Ada");
        assertThat(dto.username()).isEqualTo("ada");
        assertThat(dto.email()).isEqualTo("ada@example.com");
        assertThat(dto.isActive()).isTrue();
        assertThat(dto.roles()).containsExactly("CUSTOMER");
    }

    @Test
    void serializedResponseCarriesNoPasswordAndNoInternalFields() {
        String json = JsonMapper.builder().build().writeValueAsString(mapper.mapToDto(sampleUser()));

        assertThat(json)
                .doesNotContain("password")
                .doesNotContain(PASSWORD_HASH)
                .doesNotContain("lastLogin")
                .doesNotContain("userType")
                .doesNotContain("createdAt")
                .doesNotContain("updatedAt");
        assertThat(json).contains("\"username\":\"ada\"", "\"roles\":[\"CUSTOMER\"]");
    }

    @Test
    void handlesUserWithoutRoles() {
        User user = sampleUser();
        user.setRoles(null);

        assertThat(mapper.mapToDto(user).roles()).isEmpty();
    }
}
