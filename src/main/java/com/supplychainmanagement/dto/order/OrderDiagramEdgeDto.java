package com.supplychainmanagement.dto.order;

import com.supplychainmanagement.model.enums.OrderStatus;
import org.jspecify.annotations.Nullable;

/**
 * One arrow of the workflow drawing. {@code kind} says which way it runs: {@code FORWARD} is the flow, {@code BACK}
 * is a step that takes the order back (drawn dotted) and always carries a {@code label}; a forward arrow has one
 * only where the table gave it a few words.
 */
public record OrderDiagramEdgeDto(OrderStatus from, OrderStatus to, Kind kind, @Nullable String label) {

    public enum Kind {
        FORWARD, BACK
    }
}
