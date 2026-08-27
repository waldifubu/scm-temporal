package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Role;
import com.supplychainmanagement.entity.users.*;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.RoleRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private static final Map<RoleEnum, Class<? extends User>> USER_TYPE_CLASS_BY_ROLE = Map.of(
            RoleEnum.CUSTOMER, Customer.class,
            RoleEnum.MANAGER, Manager.class,
            RoleEnum.SUPPLIER, Supplier.class,
            RoleEnum.WAREHOUSE, Warehouse.class,
            RoleEnum.LOGISTICS, Logistics.class,
            RoleEnum.DISTRIBUTOR, Distributor.class,
            RoleEnum.ADMIN, Admin.class
    );

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public List<User> findAll() {
        return userRepository.findAll();
    }

    @Override
    public Page<User> findAll(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    @Override
    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    @Override
    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", "username", 0L));
    }

    @Override
    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", 0L));
    }

    @Override
    public User findByUsernameOrEmail(String usernameOrEmail) {
        return userRepository.findByUsernameOrEmail(usernameOrEmail, usernameOrEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", "usernameOrEmail", 0L));
    }

    @Override
    @Transactional
    public User create(User user) {
        validateUser(user, null, true);
        User prepared = prepareUserForCreate(user);
        return saveWithUserType(prepared);
    }

    @Override
    @Transactional
    public User update(Long id, User user) {
        User existing = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));

        validateUser(user, id, false);

        existing.setFirstName(user.getFirstName());
        existing.setLastName(user.getLastName());
        existing.setEmail(user.getEmail());
        existing.setUsername(user.getUsername());
        existing.setColor(user.getColor());
        existing.setIsActive(user.getIsActive() != null ? user.getIsActive() : true);

        if (user.getRoles() != null && !user.getRoles().isEmpty()) {
            existing.setRoles(user.getRoles());
        }

        if (user.getPassword() != null && !user.getPassword().isBlank()) {
            existing.setPassword(passwordEncoder.encode(user.getPassword()));
        }

        // Deliberately NO saveWithUserType here: that method builds a new instance of the subclass
        // matching the role, carrying the same id, and has it merged. While this method ran outside
        // a transaction "existing" was detached and that just about worked. Now "existing" is
        // managed - merging a different subclass with the same id collides with the already loaded
        // instance in the persistence context.
        // The user_type discriminator is mapped insertable=false, updatable=false anyway, so a type
        // change was never persistable to begin with. A plain save on the managed entity therefore
        // achieves exactly what the previous code did.
        return userRepository.save(existing);
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));

        if (user.getRoles() != null && !user.getRoles().isEmpty()) {
            user.getRoles().clear();
            userRepository.save(user);
        }

        userRepository.delete(user);
    }

    private void validateUser(User user, Long currentId, boolean isCreate) {
        if (user == null) {
            throw new APIException(HttpStatus.BAD_REQUEST, "User is required");
        }

        if (user.getFirstName() == null || user.getFirstName().isBlank()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "First name is required");
        }

        if (user.getLastName() == null || user.getLastName().isBlank()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Last name is required");
        }

        if (user.getUsername() == null || user.getUsername().isBlank()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Username is required");
        }

        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Email is required");
        }

        if (isCreate && (user.getPassword() == null || user.getPassword().isBlank())) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Password is required");
        }

        userRepository.findByUsername(user.getUsername())
                .filter(existing -> isCreate || !existing.getId().equals(currentId))
                .ifPresent(existing -> {
                    throw new APIException(HttpStatus.CONFLICT, "Username already exists");
                });

        userRepository.findByEmail(user.getEmail())
                .filter(existing -> isCreate || !existing.getId().equals(currentId))
                .ifPresent(existing -> {
                    throw new APIException(HttpStatus.CONFLICT, "Email already exists");
                });
    }

    private User prepareUserForCreate(User user) {
        User prepared = new User();
        prepared.setFirstName(user.getFirstName());
        prepared.setLastName(user.getLastName());
        prepared.setUsername(user.getUsername());
        prepared.setEmail(user.getEmail());
        prepared.setPassword(passwordEncoder.encode(user.getPassword()));
        prepared.setColor(user.getColor());
        prepared.setIsActive(user.getIsActive() != null ? user.getIsActive() : true);

        Set<Role> roles = user.getRoles() == null ? new HashSet<>() : new HashSet<>(user.getRoles());
        if (roles.isEmpty()) {
            Role defaultRole = roleRepository.findByRolename(RoleEnum.CUSTOMER)
                    .orElseThrow(() -> new APIException(HttpStatus.BAD_REQUEST, "Default role CUSTOMER not found"));
            roles.add(defaultRole);
        }
        prepared.setRoles(roles);
        return prepared;
    }

    private User saveWithUserType(User user) {
        RoleEnum primaryRole = user.getRoles() == null || user.getRoles().isEmpty()
                ? RoleEnum.CUSTOMER
                : user.getRoles().iterator().next().getRolename();

        Class<? extends User> userTypeClass = USER_TYPE_CLASS_BY_ROLE.get(primaryRole);
        if (userTypeClass == null) {
            throw new IllegalStateException("Unexpected user role: " + primaryRole);
        }

        User typedUser = BeanUtils.instantiateClass(userTypeClass);
        BeanUtils.copyProperties(user, typedUser, "roles");
        typedUser.setRoles(user.getRoles());
        return userRepository.save(typedUser);
    }
}
