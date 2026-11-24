package com.supplychainmanagement.dto.order;

import com.supplychainmanagement.model.enums.OrderStatus;

/**
 * One state of the order workflow as a drawing needs it: the status, its name in words ({@code WAIT SUPPLY}) and
 * what it means - the text shown while the pointer rests on it.
 */
public record OrderDiagramNodeDto(OrderStatus id, String label, String description) {
}
