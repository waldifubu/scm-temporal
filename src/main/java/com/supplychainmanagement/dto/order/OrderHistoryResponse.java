package com.supplychainmanagement.dto.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.supplychainmanagement.entity.OrderHistory;
import com.supplychainmanagement.model.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One row of an order's history: from which status to which, and when.
 * <p>
 * {@code previousStatus} is {@code null} on the row of the creation of the order, which comes from
 * nowhere - the <em>last</em> row of the answer, as the list is newest first. That is information and
 * stays in the JSON as a null rather than being dropped.
 * <p>
 * <strong>{@code changedById} and {@code changedByName} are for staff only.</strong> A customer reads
 * the history of their own order and sees what happened and when; who inside the house did it is not
 * theirs to know, and a name here is a name of an employee. For staff, both are also absent where the
 * row has no acting user - an automatic step such as the reservation moving an order to
 * IN_FULFILLMENT has nobody to name, and a null here means exactly that.
 */
public record OrderHistoryResponse(
        Long id,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        LocalDateTime changedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long changedById,
        @JsonInclude(JsonInclude.Include.NON_NULL) String changedByName
) {

    /**
     * @param showActor whether the caller may see who did it
     * @param names     user id to display name, for the ids this page of rows mentions - looked up once
     *                  for all of them by the caller, never per row
     */
    public static OrderHistoryResponse of(OrderHistory row, boolean showActor, Map<Long, String> names) {
        Long actor = showActor ? row.getUserId() : null;
        return new OrderHistoryResponse(
                row.getId(),
                row.getPreviousStatus(),
                row.getNewStatus(),
                row.getChangedAt(),
                actor,
                actor == null ? null : names.get(actor)
        );
    }
}
