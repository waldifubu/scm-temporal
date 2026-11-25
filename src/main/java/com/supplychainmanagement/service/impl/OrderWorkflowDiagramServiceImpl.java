package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.order.OrderDiagramEdgeDto;
import com.supplychainmanagement.dto.order.OrderDiagramNodeDto;
import com.supplychainmanagement.dto.order.OrderWorkflowDiagramResponse;
import com.supplychainmanagement.model.enums.OrderAction;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.service.OrderWorkflow;
import com.supplychainmanagement.service.OrderWorkflowDiagramService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What of the table is worth drawing, and nothing else. The rules:
 * <ul>
 *   <li><b>The ends</b> are where {@code REJECT} and {@code CANCEL} lead. They get no arrows - an arrow from every
 *   status that can be turned down would bury the flow - and nothing leads into or out of them on the drawing.</li>
 *   <li><b>Shortcuts</b> - what the table declares as such - are left out.</li>
 *   <li><b>One arrow per pair</b> of statuses, whatever the action; it keeps the first label the table gave.</li>
 *   <li><b>Forward</b> is towards a later status in {@link OrderWorkflow#statuses()}, <b>back</b> towards an earlier
 *   one. A back arrow is always labelled: the action's name when the table gave no words.</li>
 * </ul>
 * Which steps are shortcuts is the table's to say, not worked out here: an optional status (review, partly delivered)
 * would make the step around it look redundant to any rule that only reads the graph.
 */
@Service
@RequiredArgsConstructor
public class OrderWorkflowDiagramServiceImpl implements OrderWorkflowDiagramService {

    private final OrderWorkflow workflow;

    private record Step(OrderStatus from, OrderStatus to, OrderAction action, String label) {
    }

    @Override
    public OrderWorkflowDiagramResponse diagram() {
        List<OrderStatus> order = workflow.statuses();

        Set<OrderStatus> ends = EnumSet.noneOf(OrderStatus.class);
        for (OrderWorkflow.Transition row : workflow.transitions()) {
            if (row.action() == OrderAction.REJECT || row.action() == OrderAction.CANCEL) {
                ends.add(row.to());
            }
        }

        // One step per pair, the first label that is not blank wins.
        Map<String, Step> steps = new LinkedHashMap<>();
        for (OrderWorkflow.Transition row : workflow.transitions()) {
            if (row.shortcut() || ends.contains(row.to()) || ends.contains(row.from()) || row.from() == row.to()) {
                continue;
            }
            steps.merge(row.from() + ">" + row.to(),
                    new Step(row.from(), row.to(), row.action(), row.label()),
                    (kept, next) -> kept.label().isBlank() && !next.label().isBlank()
                            ? new Step(kept.from(), kept.to(), kept.action(), next.label()) : kept);
        }

        List<OrderDiagramEdgeDto> forward = new ArrayList<>();
        List<OrderDiagramEdgeDto> back = new ArrayList<>();
        for (Step step : steps.values()) {
            if (order.indexOf(step.to()) > order.indexOf(step.from())) {
                forward.add(new OrderDiagramEdgeDto(step.from(), step.to(), OrderDiagramEdgeDto.Kind.FORWARD,
                        step.label().isBlank() ? null : step.label()));
            } else {
                back.add(new OrderDiagramEdgeDto(step.from(), step.to(), OrderDiagramEdgeDto.Kind.BACK,
                        step.label().isBlank() ? step.action().name().toLowerCase().replace('_', ' ') : step.label()));
            }
        }
        List<OrderDiagramEdgeDto> edges = new ArrayList<>(forward);
        edges.addAll(back);

        List<OrderDiagramNodeDto> nodes = order.stream()
                .map(status -> new OrderDiagramNodeDto(status, status.name().replace('_', ' '),
                        workflow.describe(status)))
                .toList();

        return new OrderWorkflowDiagramResponse(nodes, edges, order.stream().filter(ends::contains).toList());
    }
}
