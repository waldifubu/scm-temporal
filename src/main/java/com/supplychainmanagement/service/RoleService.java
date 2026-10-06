package com.supplychainmanagement.service;

import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;

public interface RoleService {

    boolean isAdmin(org.springframework.security.core.userdetails.User authUser);

    /**
     * Whether the user with this id holds ADMIN, read from the stored roles rather than from anything
     * a request carried.
     * <p>
     * For a rule a service enforces on an acting user it only knows by id - "only the assigned
     * distributor reports on a shipment, unless an admin corrects it" and its counterpart on the
     * supplier side - where the principal is not at hand.
     */
    boolean isAdmin(Long userId);

    boolean isPrivilegedUser(org.springframework.security.core.userdetails.User authUser);

    /**
     * Whether the caller may read orders that are not their own: ADMIN and MANAGER, and the two
     * roles that work from an order without being its customer - WAREHOUSE, which picks and packs it,
     * and LOGISTICS, which plans its shipment and needs its due date.
     * <p>
     * Deliberately not {@link #isPrivilegedUser}, which is ADMIN and MANAGER and answers other
     * questions too (which product fields a caller sees, whose orders a list shows). Reading orders
     * used to ask <em>that</em>, so WAREHOUSE and LOGISTICS passed {@code @PreAuthorize} on
     * {@code GET /orders/{orderNo}} and were then refused by the service as "another customer" for every
     * order there is. A rule that two layers must agree on belongs in one named place.
     */
    boolean canReadAnyOrder(org.springframework.security.core.userdetails.User authUser);

    /**
     * Checks whether the caller holds at least one of the given roles.
     * A {@code null} principal (unauthenticated) yields {@code false} instead of an NPE.
     */
    boolean hasAnyAuthority(org.springframework.security.core.userdetails.User authUser, RoleEnum... roles);

    /**
     * Like {@link #hasAnyAuthority}, but throws a
     * {@link com.supplychainmanagement.exception.WrongRoleException} (HTTP 403) when the authority
     * is missing.
     * <p>
     * For endpoints, prefer {@code @PreAuthorize} - the rule then lives in exactly one place. This
     * method is meant for checks an annotation cannot express, for instance when the required role
     * only follows from the payload.
     */
    void requireAnyAuthority(org.springframework.security.core.userdetails.User authUser, RoleEnum... roles);

    void convertToAdmin(User user);
}
