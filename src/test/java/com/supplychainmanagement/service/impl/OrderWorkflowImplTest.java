package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.model.enums.OrderAction;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.service.OrderWorkflow;
import com.supplychainmanagement.service.OrderWorkflow.Transition;
import com.supplychainmanagement.service.OrderWorkflow.Trigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.supplychainmanagement.model.enums.OrderAction.ACCEPT;
import static com.supplychainmanagement.model.enums.OrderAction.CANCEL;
import static com.supplychainmanagement.model.enums.OrderAction.CLOSE;
import static com.supplychainmanagement.model.enums.OrderAction.COMPLETE;
import static com.supplychainmanagement.model.enums.OrderAction.REJECT;
import static com.supplychainmanagement.model.enums.OrderAction.RELEASE;
import static com.supplychainmanagement.model.enums.OrderAction.RESERVE;
import static com.supplychainmanagement.model.enums.OrderAction.SHIPMENT_PROGRESS;
import static com.supplychainmanagement.model.enums.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The table itself. The rows below are the rules the services used to keep in guards of their own, written down
 * once more as what they must be - so changing the table without meaning to turns a case red, and so does an enum
 * value that nobody put into it.
 */
class OrderWorkflowImplTest {

    private final OrderWorkflow workflow = new OrderWorkflowImpl();

    private static Set<OrderStatus> statuses(OrderStatus... statuses) {
        return statuses.length == 0 ? EnumSet.noneOf(OrderStatus.class) : EnumSet.copyOf(Arrays.asList(statuses));
    }

    // ------------------------------------------------------------------ the statuses

    @Test
    void namesEveryStatusExactlyOnce() {
        assertThat(workflow.statuses()).doesNotHaveDuplicates()
                .containsExactlyInAnyOrder(OrderStatus.values());
    }

    @Test
    void startsInCreatedAndReachesEveryStatusFromThere() {
        assertThat(workflow.initialStatus()).isEqualTo(CREATED);

        Set<OrderStatus> reached = EnumSet.of(CREATED);
        Deque<OrderStatus> open = new ArrayDeque<>(List.of(CREATED));
        while (!open.isEmpty()) {
            OrderStatus status = open.pop();
            for (Transition row : workflow.transitions()) {
                if (row.from() == status && reached.add(row.to())) {
                    open.push(row.to());
                }
            }
        }

        // A status nothing leads to would be a dead value in the enum and in every CHECK that lists it.
        assertThat(reached).containsExactlyInAnyOrder(OrderStatus.values());
    }

    @Test
    void theEndsAreRejectedAndCompletedAndACancelledOrderCanOnlyBeClosed() {
        Set<OrderStatus> ends = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            if (workflow.isFinal(status)) {
                ends.add(status);
            }
        }

        assertThat(ends).containsExactlyInAnyOrder(REJECTED, COMPLETED);
        for (OrderStatus end : ends) {
            assertThat(workflow.actionsFrom(end)).as("actions from " + end).isEmpty();
        }
        assertThat(workflow.actionsFrom(CANCELLED)).containsExactly(CLOSE);
        assertThat(workflow.targetsOf(CANCELLED, CLOSE)).containsExactly(COMPLETED);
        assertThat(workflow.sourcesOf(CLOSE)).containsExactly(CANCELLED);
    }

    // ------------------------------------------------------------------ where each step may be taken

    @Test
    void acceptsAndReviewsOnlyWhileTheOrderIsStillBeingDecided() {
        assertThat(workflow.sourcesOf(ACCEPT)).isEqualTo(statuses(CREATED, REVIEW));
        assertThat(workflow.sourcesOf(OrderAction.REVIEW)).isEqualTo(statuses(CREATED, REVIEW));
    }

    @Test
    void anAcceptanceLeadsToAcknowledgedOrWaitSupply() {
        assertThat(workflow.targetsOf(CREATED, ACCEPT)).isEqualTo(statuses(ACKNOWLEDGED, WAIT_SUPPLY));
        assertThat(workflow.targetsOf(REVIEW, ACCEPT)).isEqualTo(statuses(ACKNOWLEDGED, WAIT_SUPPLY));
        assertThat(workflow.targetsOf(IN_FULFILLMENT, ACCEPT)).isEmpty();
    }

    @Test
    void rejectsAndCancelsUpToTheMomentGoodsAreInAShipment() {
        Set<OrderStatus> before = statuses(CREATED, REVIEW, ACKNOWLEDGED, WAIT_SUPPLY, IN_FULFILLMENT);

        assertThat(workflow.sourcesOf(REJECT)).isEqualTo(before);
        assertThat(workflow.sourcesOf(CANCEL)).isEqualTo(before);
        assertThat(workflow.targetsOf(IN_FULFILLMENT, REJECT)).containsExactly(REJECTED);
        assertThat(workflow.targetsOf(IN_FULFILLMENT, CANCEL)).containsExactly(CANCELLED);
    }

    @Test
    void aReservationLiftsAnOrderOutOfEveryStatusBeforeFulfillmentIncludingWaitSupply() {
        assertThat(workflow.sourcesOf(RESERVE)).isEqualTo(statuses(CREATED, ACKNOWLEDGED, REVIEW, WAIT_SUPPLY));
        assertThat(workflow.targetsOf(WAIT_SUPPLY, RESERVE)).containsExactly(IN_FULFILLMENT);
    }

    @Test
    void aFullReleaseSendsAnOrderInFulfillmentBackToReview() {
        assertThat(workflow.sourcesOf(RELEASE)).isEqualTo(statuses(IN_FULFILLMENT));
        assertThat(workflow.targetsOf(IN_FULFILLMENT, RELEASE)).containsExactly(REVIEW);
    }

    @Test
    void theShipmentsOwnTheStretchFromFulfillmentToDelivered() {
        assertThat(workflow.sourcesOf(SHIPMENT_PROGRESS)).isEqualTo(
                statuses(IN_FULFILLMENT, READY_FOR_DISPATCH, IN_TRANSIT, PARTIALLY_DELIVERED));

        // Forward as shipments advance, and one step back when a shipment is called off - and no other way back.
        assertThat(workflow.allowsMove(READY_FOR_DISPATCH, IN_FULFILLMENT)).isTrue();
        assertThat(workflow.allowsMove(IN_TRANSIT, IN_FULFILLMENT)).isFalse();
        assertThat(workflow.allowsMove(DELIVERED, PARTIALLY_DELIVERED)).isFalse();
        assertThat(workflow.allowsMove(PARTIALLY_DELIVERED, IN_TRANSIT)).isFalse();
    }

    @Test
    void anOrderIsCompletedFromDeliveredOrPartlyDelivered() {
        assertThat(workflow.sourcesOf(COMPLETE)).isEqualTo(statuses(DELIVERED, PARTIALLY_DELIVERED));
        assertThat(workflow.targetsOf(DELIVERED, COMPLETE)).containsExactly(COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(OrderAction.class)
    void everyActionHasAPlaceToBeTaken(OrderAction action) {
        assertThat(workflow.sourcesOf(action)).as(action + " is in the table").isNotEmpty();
    }

    // ------------------------------------------------------------------ the shape of the table

    @Test
    void hasNoStepTwice() {
        Set<String> seen = new HashSet<>();
        for (Transition row : workflow.transitions()) {
            assertThat(seen.add(row.from() + ">" + row.to() + ">" + row.action()))
                    .as(row.from() + " -> " + row.to() + " by " + row.action()).isTrue();
        }
    }

    @Test
    void everyStepNamesWhoTakesIt() {
        for (Transition row : workflow.transitions()) {
            assertThat(row.actors()).as(row.from() + " -> " + row.to()).isNotEmpty();
        }
    }

    @Test
    void shipmentProgressAndClosingAreComputedAndEverythingElseIsAsked() {
        for (Transition row : workflow.transitions()) {
            assertThat(row.trigger()).as(row.action().name())
                    .isEqualTo(row.action() == SHIPMENT_PROGRESS || row.action() == CLOSE
                            ? Trigger.COMPUTED : Trigger.MANUAL);
        }
    }

    @Test
    void whoMayTakeWhatFollowsTheEndpoints() {
        assertThat(actorsOf(ACCEPT)).containsExactlyInAnyOrder(RoleEnum.ADMIN, RoleEnum.MANAGER);
        assertThat(actorsOf(CANCEL)).containsExactlyInAnyOrder(RoleEnum.ADMIN, RoleEnum.MANAGER);
        // The customer closes their own order - and the endpoint checks that it is theirs.
        assertThat(actorsOf(COMPLETE)).containsExactlyInAnyOrder(RoleEnum.ADMIN, RoleEnum.MANAGER, RoleEnum.CUSTOMER);
        assertThat(actorsOf(SHIPMENT_PROGRESS)).contains(RoleEnum.WAREHOUSE, RoleEnum.LOGISTICS, RoleEnum.DISTRIBUTOR);
    }

    private Set<RoleEnum> actorsOf(OrderAction action) {
        Set<RoleEnum> roles = EnumSet.noneOf(RoleEnum.class);
        workflow.transitions().stream().filter(row -> row.action() == action)
                .forEach(row -> roles.addAll(row.actors()));
        return roles;
    }

    @Test
    void anAutomaticStepIsOneASweepTakesToo() {
        assertThat(workflow.transitions().stream().filter(Transition::automatic).map(Transition::action)
                .distinct().toList()).containsExactlyInAnyOrder(ACCEPT, RESERVE, RELEASE, CLOSE);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void everyStatusIsExplained(OrderStatus status) {
        assertThat(workflow.describe(status)).as(status.name()).isNotBlank();
    }

    @Test
    void declaresTheShortcutsAndOnlyThose() {
        List<String> shortcuts = workflow.transitions().stream().filter(Transition::shortcut)
                .map(row -> row.from() + ">" + row.to()).toList();

        assertThat(shortcuts).containsExactlyInAnyOrder(
                "CREATED>IN_FULFILLMENT", "REVIEW>IN_FULFILLMENT",
                "IN_FULFILLMENT>IN_TRANSIT", "IN_FULFILLMENT>PARTIALLY_DELIVERED", "IN_FULFILLMENT>DELIVERED",
                "READY_FOR_DISPATCH>PARTIALLY_DELIVERED", "READY_FOR_DISPATCH>DELIVERED",
                "PARTIALLY_DELIVERED>COMPLETED");
        // A shortcut is still a step: the table allows it and changeStatus lets it through.
        assertThat(workflow.allowsMove(OrderStatus.CREATED, OrderStatus.IN_FULFILLMENT)).isTrue();
    }

    @Test
    void labelsTheStepsThatTakeAnOrderBack() {
        assertThat(workflow.find(OrderStatus.IN_FULFILLMENT, OrderStatus.REVIEW).orElseThrow().label())
                .isEqualTo("all released");
        assertThat(workflow.find(OrderStatus.READY_FOR_DISPATCH, OrderStatus.IN_FULFILLMENT).orElseThrow().label())
                .isEqualTo("shipment cancelled");
    }

    // ------------------------------------------------------------------ built to move onto Temporal

    /**
     * Workflow code has to be deterministic, so the table keeps no state and reaches for nothing: no field that
     * can change, no collaborator. Held as a test because the day somebody injects a repository "just to check
     * the stock" is the day it stops being movable.
     */
    @Test
    void isAPureFunctionOfItsArguments() {
        for (Field field : OrderWorkflowImpl.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            assertThat(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()))
                    .as("field " + field.getName() + " is a static final constant").isTrue();
        }
    }

    @Test
    void answersTheSameEveryTime() {
        assertThat(new OrderWorkflowImpl().transitions()).isEqualTo(workflow.transitions());
    }
}
