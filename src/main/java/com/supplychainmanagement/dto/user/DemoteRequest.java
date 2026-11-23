package com.supplychainmanagement.dto.user;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.supplychainmanagement.model.enums.RoleEnum;
import jakarta.validation.constraints.NotNull;

/**
 * What an administrator is taken back to: {@code POST /users/{id}/demote}.
 *
 * @param role the one role (and with it the type) the user keeps; anything but ADMIN
 */
public record DemoteRequest(
        // On the property, not the enum - see CreatePackageRequest.shipmentPackageType.
        @NotNull @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_VALUES)
        RoleEnum role
) {
}
