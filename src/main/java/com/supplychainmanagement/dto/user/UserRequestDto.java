package com.supplychainmanagement.dto.user;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.supplychainmanagement.model.enums.RoleEnum;

import java.util.Set;

/**
 * What a client may send to create or update a user - and nothing else.
 * <p>
 * Deliberately not the {@code User} entity: bound from a body, that one accepted every field it
 * has - id, userType, createdAt, lastLogin and full Role entities included. Anything not listed
 * here is ignored now.
 * <p>
 * Roles travel as names, the same way {@link UserDto} answers with them; UserServiceImpl looks the
 * Role entities up. A missing or empty list keeps the previous behaviour: CUSTOMER on create, the
 * current roles on update. Password and isActive are optional on update, as before.
 */
public record UserRequestDto(
        String firstName,
        String lastName,
        String username,
        String email,
        String password,
        String color,
        Boolean isActive,
        // On the property, not the enum - see CreatePackageRequest.shipmentPackageType.
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_VALUES)
        Set<RoleEnum> roles
) {
}
