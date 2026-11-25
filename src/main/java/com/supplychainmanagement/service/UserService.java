package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.user.DistributorQueryRequest;
import com.supplychainmanagement.dto.user.UserDto;
import com.supplychainmanagement.dto.user.UserRequestDto;
import com.supplychainmanagement.entity.users.Distributor;
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

    /**
     * Creates a user from what a client sent - see {@link UserRequestDto}. Only an ADMIN may hand out
     * the ADMIN role (403 otherwise); {@code actor} is the authenticated caller.
     */
    User create(UserRequestDto request, org.springframework.security.core.userdetails.User actor);

    /** Updates a user from what a client sent - see {@link UserRequestDto}. */
    User update(Long id, UserRequestDto request);

    /**
     * Makes the user an administrator: {@code user_type} becomes {@code admin} and ADMIN is the only role left in
     * {@code users_roles} - whatever rows the user had there, an ADMIN row included, are deleted first, so the pair
     * can never exist twice. 404 for an unknown user, 409 for one that already is an administrator.
     */
    User promoteToAdmin(Long id);

    /**
     * Takes an administrator back to one ordinary role: type and roles follow {@code role} (never ADMIN, 400). 404
     * for an unknown user, 409 for one who is no administrator or for the last administrator there is.
     */
    User demote(Long id, com.supplychainmanagement.model.enums.RoleEnum role);

    void deleteById(Long id);

    User findByUsernameOrEmail(String usernameOrEmail);

    Long getAuthenticatedUserId(org.springframework.security.core.userdetails.User authUser);

    /**
     * The user behind a token, as the API shows them: the same {@link UserDto} {@code GET /users/{id}}
     * answers, with <strong>every</strong> role the user holds.
     * <p>
     * Mapped here, while the session is open, rather than by the caller afterwards: the roles are LAZY.
     *
     * @param usernameOrEmail what the authenticated principal is called - the token carries the user name
     */
    UserDto findCurrentUser(String usernameOrEmail);

    /**
     * The distributors a shipment can be handed to, active accounts only: the answer to "which
     * id goes into {@code POST /shipments/assign/{distributorId}}". See {@link #findSuppliers} for the suppliers.
     */
    Page<User> findDistributors(Pageable pageable);

    /**
     * The suppliers, active accounts only: the ones a supplier rule can name. See
     * {@link #findDistributors} for the carriers.
     */
    Page<User> findSuppliers(Pageable pageable);

    /**
     * The carriers the rules ({@code DistributorRule}) name for a customer, a weight and a volume, best first:
     * the rule that sets the most dimensions leads, the priority decides between equals, and a carrier named by
     * several rules appears once, at its best place. Only active carriers. Empty when no rule applies.
     */
    List<Distributor> queryDistributors(DistributorQueryRequest request);

}
