package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.order.OrderWorkflowDiagramResponse;

/**
 * Prepares {@link OrderWorkflow} for a drawing. A service of its own and not part of the workflow: choosing what to
 * draw is presentation, and the workflow itself has to stay what a workflow engine could take over.
 */
public interface OrderWorkflowDiagramService {

    OrderWorkflowDiagramResponse diagram();
}
