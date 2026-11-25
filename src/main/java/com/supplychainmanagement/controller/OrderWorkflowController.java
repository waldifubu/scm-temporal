package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.order.OrderWorkflowDiagramResponse;
import com.supplychainmanagement.dto.order.OrderWorkflowResponse;
import com.supplychainmanagement.service.OrderWorkflow;
import com.supplychainmanagement.service.OrderWorkflowDiagramService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The order workflow as data. A controller of its own, next to {@code OrderController}, because it needs none of
 * that one's collaborators - it reads the table and nothing else. The literal segment wins over
 * {@code GET /orders/{orderNo}}.
 */
@RestController
@RequestMapping({"/api/{version}/orders"})
@RequiredArgsConstructor
public class OrderWorkflowController {

    private final OrderWorkflow workflow;
    private final OrderWorkflowDiagramService diagramService;

    /**
     * Statuses and steps, the same for every caller: who may take a step is part of the answer, not a filter on it.
     * Open to every role that reads orders, and to the carrier, whose start page shows it.
     */
    @GetMapping(path = "/workflow", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE','LOGISTICS','CUSTOMER','DISTRIBUTOR')")
    public OrderWorkflowResponse getWorkflow() {
        return OrderWorkflowResponse.of(workflow);
    }

    /**
     * The same workflow prepared for drawing: nodes with their names and what each status means, the arrows worth
     * drawing (shortcuts left out, back steps labelled) and the statuses the flow ends in. The same answer for
     * everybody, and open to every role - the supplier too, whose start page shows it, although they read no orders and
     * therefore not the table above. The literal path wins over {@code /{orderNo}/shipments} like its sibling.
     */
    @GetMapping(path = "/workflow/diagram", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE','LOGISTICS','CUSTOMER','DISTRIBUTOR','SUPPLIER')")
    public OrderWorkflowDiagramResponse getDiagram() {
        return diagramService.diagram();
    }
}
