package com.supplychainmanagement.dto.component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.model.enums.RequestStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One placed component request, as the API answers it.
 * <p>
 * A DTO rather than the entity: {@code RequestComponent} points at its component and its supplier,
 * and the component points back at its product - handing that to the response writer resolves the
 * whole graph one LAZY reference at a time.
 */
public record RequestComponentResponse(
        Long id,
        Long componentDbId,
        UUID componentId,
        String componentName,
        Long qty,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String comment,
        RequestStatus requestStatus,
        LocalDateTime requestDate,
        Long supplierId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String supplierName
) {

    /** Mapped inside the transaction, like every other answer - the references are LAZY. */
    public static RequestComponentResponse from(RequestComponent request) {
        var component = request.getComponent();
        var supplier = request.getSupplier();

        return new RequestComponentResponse(
                request.getId(),
                component != null ? component.getId() : null,
                component != null ? component.getSku() : null,
                component != null ? component.getName() : null,
                request.getQty(),
                request.getComment(),
                request.getRequestStatus(),
                request.getRequestDate(),
                supplier != null ? supplier.getId() : null,
                nameOf(supplier));
    }

    private static String nameOf(com.supplychainmanagement.entity.users.User user) {
        if (user == null) {
            return null;
        }

        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return name.isEmpty() ? user.getUsername() : name;
    }
}
