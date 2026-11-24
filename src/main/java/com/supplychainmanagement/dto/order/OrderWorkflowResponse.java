package com.supplychainmanagement.dto.order;

import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.service.OrderWorkflow;

import java.util.List;

/**
 * The life of an order as a table ({@code GET /orders/workflow}): every status in the order a healthy order passes
 * them, the one a new order starts in, and every step between them. A client builds its buttons, its graph and its
 * explanations from this instead of repeating the rules.
 */
public record OrderWorkflowResponse(
        OrderStatus initialStatus,
        List<OrderStatus> statuses,
        List<OrderTransitionDto> transitions
) {
    public static OrderWorkflowResponse of(OrderWorkflow workflow) {
        return new OrderWorkflowResponse(workflow.initialStatus(), workflow.statuses(),
                workflow.transitions().stream().map(OrderTransitionDto::of).toList());
    }
}
