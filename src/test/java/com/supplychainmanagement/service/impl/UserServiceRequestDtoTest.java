package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.user.UserRequestDto;
import com.supplychainmanagement.entity.Role;
import com.supplychainmanagement.entity.users.Manager;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.RoleRepository;
import com.supplychainmanagement.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How a UserRequestDto becomes a user: role names looked up, everything else as before. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceRequestDtoTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserServiceImpl service;

    private static Role role(RoleEnum name) {
        Role role = new Role();
        role.setRolename(name);
        return role;
    }

    private static UserRequestDto request(Set<RoleEnum> roles) {
        return new UserRequestDto("Ada", "Lovelace", "ada", "ada@example.com", "secret1", null, null, roles);
    }

    private void savesWhatItIsGiven() {
        when(passwordEncoder.encode(anyString())).thenAnswer(call -> "hashed:" + call.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
    }

    /** The role name becomes the stored Role entity, and it still decides the user type. */
    @Test
    void createLooksUpTheRolesByName() {
        savesWhatItIsGiven();
        Role manager = role(RoleEnum.MANAGER);
        when(roleRepository.findByRolename(RoleEnum.MANAGER)).thenReturn(Optional.of(manager));

        User created = service.create(request(Set.of(RoleEnum.MANAGER)));

        assertThat(created).isInstanceOf(Manager.class);
        assertThat(created.getRoles()).containsExactly(manager);
        assertThat(created.getPassword()).isEqualTo("hashed:secret1");
        assertThat(created.getIsActive()).isTrue();
    }

    /** No roles sent: the default CUSTOMER applies, as it did with the entity. */
    @Test
    void createFallsBackToCustomerWithoutRoles() {
        savesWhatItIsGiven();
        Role customer = role(RoleEnum.CUSTOMER);
        when(roleRepository.findByRolename(RoleEnum.CUSTOMER)).thenReturn(Optional.of(customer));

        User created = service.create(request(null));

        assertThat(created.getRoles()).containsExactly(customer);
    }

    /** A role the database does not hold is a 400, before anything is saved. */
    @Test
    void refusesARoleThatDoesNotExist() {
        savesWhatItIsGiven();
        when(roleRepository.findByRolename(RoleEnum.LOGISTICS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(Set.of(RoleEnum.LOGISTICS))))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Role LOGISTICS not found");
        verify(userRepository, never()).save(any());
    }

    /** Update without roles keeps the current ones; the fields sent are applied. */
    @Test
    void updateKeepsTheRolesWhenNoneAreSent() {
        savesWhatItIsGiven();
        Role customer = role(RoleEnum.CUSTOMER);
        User existing = new User();
        existing.setId(9L);
        existing.setRoles(Set.of(customer));
        when(userRepository.findById(9L)).thenReturn(Optional.of(existing));

        User updated = service.update(9L, new UserRequestDto("Grace", "Hopper", "grace",
                "grace@example.com", null, "#ff0000", null, null));

        assertThat(updated.getRoles()).containsExactly(customer);
        assertThat(updated.getFirstName()).isEqualTo("Grace");
        assertThat(updated.getColor()).isEqualTo("#ff0000");
    }
}
