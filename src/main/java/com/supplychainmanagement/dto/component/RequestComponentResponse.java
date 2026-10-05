package com.supplychainmanagement.dto.component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RequestStatus;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        /*
         * Who last moved the request on, by id and name - the id alone would make every caller look
         * the user up again, and the name alone could not be followed. Null for a row placed before
         * the column existed, or for a step nobody could be resolved for.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long assignedById,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String assignedByName,
        /** When that last change happened - stamped by Hibernate, not written by hand. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime updated
) {

    /**
     * Mapped inside the transaction, like every other answer - the references are LAZY.
     * <p>
     * {@code assignedBy} is read for its name, which initializes that proxy: a finder feeding this
     * has to fetch the user along, or it costs a query per row.
     */
    public static RequestComponentResponse from(RequestComponent request) {
        var component = request.getComponent();
        var supplier = request.getSupplier();
        var assignedBy = request.getAssignedBy();

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
                assignedBy != null ? assignedBy.getId() : null,
                assignedBy != null ? nameOf(assignedBy) : null,
                request.getUpdated()
        );
    }

    /** First and last name where there is one, the user name otherwise - never an empty string. */
    private static String nameOf(User user) {
        String full = Stream.of(user.getFirstName(), user.getLastName())
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" "));
        return full.isBlank() ? user.getUsername() : full;
    }
}
