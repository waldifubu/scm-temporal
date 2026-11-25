package com.supplychainmanagement.dto.order;

import com.supplychainmanagement.model.enums.OrderStatus;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

/**
 * One row of the list of <em>all</em> status changes ({@code GET /orders/history}): the step as
 * {@link OrderHistoryResponse} shows it, plus which order it belongs to and whose order that is. Built by
 * {@code OrderHistoryEntryMapper}.
 * <p>
 * Staff only - the endpoint is closed to customers - so the actor is always shown. Where the row has no acting user
 * (an automatic step) {@code changedById} is {@code null} and {@code changedByName} reads "system".
 */
public record OrderHistoryEntryDto(
        Long id,
        Long orderNo,
        @Nullable Long customerId,
        @Nullable String customerName,
        @Nullable OrderStatus previousStatus,
        OrderStatus newStatus,
        LocalDateTime changedAt,
        @Nullable Long changedById,
        @Nullable String changedByName
) {
}
