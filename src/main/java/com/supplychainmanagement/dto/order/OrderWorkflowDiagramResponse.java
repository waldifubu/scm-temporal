package com.supplychainmanagement.dto.order;

import com.supplychainmanagement.model.enums.OrderStatus;

import java.util.List;

/**
 * The order workflow prepared for drawing ({@code GET /orders/workflow/diagram}): every status as a node in the
 * order a healthy order passes them, the arrows worth drawing and the statuses the flow ends in.
 * <p>
 * Not the table: {@code GET /orders/workflow} lists every step, this keeps what a person can read. A shortcut
 * that skips a status another route passes anyway is left out, {@code REJECT} and {@code CANCEL} - which would
 * start an arrow in half the nodes - are not arrows but the {@code ends}, and no arrow leads into them. The
 * renderer is the client's: nothing here is specific to Mermaid.
 */
public record OrderWorkflowDiagramResponse(
        List<OrderDiagramNodeDto> nodes,
        List<OrderDiagramEdgeDto> edges,
        List<OrderStatus> ends
) {
}
