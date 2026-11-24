package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.order.OrderDiagramEdgeDto;
import com.supplychainmanagement.dto.order.OrderDiagramNodeDto;
import com.supplychainmanagement.dto.order.OrderWorkflowDiagramResponse;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.service.OrderWorkflow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.supplychainmanagement.model.enums.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What of the table is drawn. The expected arrows are the ones the frontend used to keep by hand; now they come
 * from the table, so a change to either shows up here.
 */
class OrderWorkflowDiagramServiceImplTest {

    private final OrderWorkflow workflow = new OrderWorkflowImpl();
    private final OrderWorkflowDiagramResponse diagram = new OrderWorkflowDiagramServiceImpl(workflow).diagram();

    private static String edge(OrderDiagramEdgeDto edge) {
        return edge.from() + ">" + edge.to() + " " + edge.kind() + (edge.label() == null ? "" : " " + edge.label());
    }

    @Test
    void drawsTheFlowAsThePeopleReadIt() {
        assertThat(diagram.edges().stream().map(OrderWorkflowDiagramServiceImplTest::edge)).containsExactly(
                "CREATED>REVIEW FORWARD",
                "CREATED>ACKNOWLEDGED FORWARD",
                "CREATED>WAIT_SUPPLY FORWARD",
                "REVIEW>ACKNOWLEDGED FORWARD",
                "REVIEW>WAIT_SUPPLY FORWARD",
                "ACKNOWLEDGED>IN_FULFILLMENT FORWARD",
                "WAIT_SUPPLY>IN_FULFILLMENT FORWARD",
                "IN_FULFILLMENT>READY_FOR_DISPATCH FORWARD",
                "READY_FOR_DISPATCH>IN_TRANSIT FORWARD",
                "IN_TRANSIT>PARTIALLY_DELIVERED FORWARD",
                "IN_TRANSIT>DELIVERED FORWARD",
                "PARTIALLY_DELIVERED>DELIVERED FORWARD",
                "DELIVERED>COMPLETED FORWARD",
                "IN_FULFILLMENT>REVIEW BACK all released",
                "READY_FOR_DISPATCH>IN_FULFILLMENT BACK shipment cancelled");
    }

    @Test
    void leavesTheShortcutsOutThatTheTableDeclares() {
        List<String> drawn = diagram.edges().stream().map(e -> e.from() + ">" + e.to()).toList();

        assertThat(drawn).doesNotContain(
                "CREATED>IN_FULFILLMENT", "REVIEW>IN_FULFILLMENT", "IN_FULFILLMENT>IN_TRANSIT",
                "IN_FULFILLMENT>DELIVERED", "READY_FOR_DISPATCH>DELIVERED", "PARTIALLY_DELIVERED>COMPLETED");
        // ... but the step around an optional status stays: nothing says an order goes through review.
        assertThat(drawn).contains("CREATED>ACKNOWLEDGED", "IN_TRANSIT>DELIVERED");
    }

    @Test
    void theEndsStandApartWithoutArrows() {
        assertThat(diagram.ends()).containsExactly(REJECTED, CANCELLED);
        assertThat(diagram.edges()).noneMatch(e -> e.to() == REJECTED || e.to() == CANCELLED
                || e.from() == REJECTED || e.from() == CANCELLED);
    }

    @Test
    void everyBackArrowIsLabelledAndNoForwardOneIsEmptyBySilence() {
        for (OrderDiagramEdgeDto edge : diagram.edges()) {
            if (edge.kind() == OrderDiagramEdgeDto.Kind.BACK) {
                assertThat(edge.label()).as(edge.from() + ">" + edge.to()).isNotBlank();
            } else {
                assertThat(edge.label()).as(edge.from() + ">" + edge.to()).isNull();
            }
        }
    }

    @Test
    void hasANodeForEveryStatusWithItsNameAndMeaning() {
        assertThat(diagram.nodes().stream().map(OrderDiagramNodeDto::id))
                .containsExactlyElementsOf(workflow.statuses());
        for (OrderDiagramNodeDto node : diagram.nodes()) {
            assertThat(node.label()).as(node.id().name()).isEqualTo(node.id().name().replace('_', ' '));
            assertThat(node.description()).as(node.id().name()).isNotBlank();
        }
    }

    @Test
    void drawsNoArrowThatTheTableDoesNotAllow() {
        for (OrderDiagramEdgeDto edge : diagram.edges()) {
            assertThat(workflow.allowsMove(edge.from(), edge.to())).as(edge.from() + ">" + edge.to()).isTrue();
        }
    }

    @Test
    void everyStatusButTheStartIsReachedByAnArrowOrIsAnEnd() {
        for (OrderStatus status : OrderStatus.values()) {
            boolean reached = status == CREATED || diagram.ends().contains(status)
                    || diagram.edges().stream().anyMatch(e -> e.to() == status);
            assertThat(reached).as(status + " is on the drawing").isTrue();
        }
    }
}
