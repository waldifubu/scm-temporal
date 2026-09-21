package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.user.UserRequestDto;
import com.supplychainmanagement.entity.users.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Deliberately synchronous - see {@link OrderService}: with Mono/Flux return types the
 * {@code @Transactional} proxy committed before the actual database work had even started.
 */
public interface UserService {

    List<User> findAll();

    Page<User> findAll(Pageable pageable);

    User findById(Long id);

    User findByUsername(String username);

    User findByEmail(String email);

    User create(User user);

    User update(Long id, User user);

    /** Creates a user from what a client sent - see {@link UserRequestDto}. */
    User create(UserRequestDto request);

    /** Updates a user from what a client sent - see {@link UserRequestDto}. */
    User update(Long id, UserRequestDto request);

    void deleteById(Long id);

    User findByUsernameOrEmail(String usernameOrEmail);

    Long getAuthenticatedUserId(org.springframework.security.core.userdetails.User authUser);
}
