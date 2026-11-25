package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.model.enums.OrderAction;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.service.OrderWorkflow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.supplychainmanagement.model.enums.OrderAction.ACCEPT;
import static com.supplychainmanagement.model.enums.OrderAction.CANCEL;
import static com.supplychainmanagement.model.enums.OrderAction.CLOSE;
import static com.supplychainmanagement.model.enums.OrderAction.COMPLETE;
import static com.supplychainmanagement.model.enums.OrderAction.REJECT;
import static com.supplychainmanagement.model.enums.OrderAction.RELEASE;
import static com.supplychainmanagement.model.enums.OrderAction.RESERVE;
import static com.supplychainmanagement.model.enums.OrderAction.SHIPMENT_PROGRESS;
import static com.supplychainmanagement.model.enums.OrderStatus.*;
import static com.supplychainmanagement.model.enums.RoleEnum.ADMIN;
import static com.supplychainmanagement.model.enums.RoleEnum.CUSTOMER;
import static com.supplychainmanagement.model.enums.RoleEnum.DISTRIBUTOR;
import static com.supplychainmanagement.model.enums.RoleEnum.LOGISTICS;
import static com.supplychainmanagement.model.enums.RoleEnum.MANAGER;
import static com.supplychainmanagement.model.enums.RoleEnum.WAREHOUSE;

/**
 * The table, written out. See {@link OrderWorkflow} for what it is for and what it must stay free of: nothing here
 * may read a database, a clock or the current user.
 * <p>
 * Where the rows come from: the guards that used to sit in {@code OrderServiceImpl} (accept, review, reject, cancel,
 * complete), the pre-fulfillment list of {@code FulfillmentServiceImpl} (reserve) and its fall back to {@code REVIEW}
 * (release), and the range {@code OrderProgressServiceImpl} recomputes. They now read this table, so a row cannot
 * disagree with the check that enforces it.
 * <p>
 * {@code OrderAction.REVIEW} is written out in full below because the status of the same name is imported.
 */
@Component
public class OrderWorkflowImpl implements OrderWorkflow {

    private static final Set<RoleEnum> MANAGEMENT = Collections.unmodifiableSet(EnumSet.of(ADMIN, MANAGER));

    /** Whose work moves an order through the shipments: the house packs and plans, the carrier reports. */
    private static final Set<RoleEnum> SHIPMENT_SIDE =
            Collections.unmodifiableSet(EnumSet.of(ADMIN, WAREHOUSE, LOGISTICS, DISTRIBUTOR));

    private static final Set<RoleEnum> MAY_COMPLETE =
            Collections.unmodifiableSet(EnumSet.of(ADMIN, MANAGER, CUSTOMER));

    /** Where an order is still its own business - nothing has left the shelf yet. */
    private static final List<OrderStatus> BEFORE_DISPATCH =
            List.of(CREATED, REVIEW, ACKNOWLEDGED, WAIT_SUPPLY, IN_FULFILLMENT);

    private static final List<OrderStatus> STATUSES = List.of(
            CREATED, REVIEW, ACKNOWLEDGED, WAIT_SUPPLY, IN_FULFILLMENT, READY_FOR_DISPATCH, IN_TRANSIT,
            PARTIALLY_DELIVERED, DELIVERED, COMPLETED, REJECTED, CANCELLED);

    private static final List<Transition> TRANSITIONS = build();

    /** What each status means, in the words of the process: shown beside the workflow diagram. */
    private static final Map<OrderStatus, String> DESCRIPTIONS = descriptions();

    private static Map<OrderStatus, String> descriptions() {
        Map<OrderStatus, String> text = new EnumMap<>(OrderStatus.class);
        text.put(CREATED, "The customer has placed the order. It waits to be acknowledged.");
        text.put(ACKNOWLEDGED,
                "The order is accepted and a delivery date is confirmed. Every line can be covered from stock.");
        text.put(WAIT_SUPPLY, "The order is confirmed, but stock is missing for at least one line. It waits for "
                + "supply and moves on as soon as stock can be reserved.");
        text.put(REVIEW, "A manager has picked the order up and not answered yet. An order whose reservations "
                + "were all released lands here again. Accept or reject it from here.");
        text.put(IN_FULFILLMENT,
                "Stock is reserved. The lines are being picked and packed, and the packages put into shipments.");
        text.put(READY_FOR_DISPATCH, "Every line is in a shipment that has been reported ready. The order waits "
                + "for a carrier to take it.");
        text.put(IN_TRANSIT, "Every line is on the road with a carrier.");
        text.put(PARTIALLY_DELIVERED, "Part of the order has arrived while the rest is still on its way.");
        text.put(DELIVERED, "Everything has arrived. The order can now be completed.");
        text.put(COMPLETED, "The order is closed: the delivery was confirmed, or it was cancelled before. "
                + "This is the end of the flow.");
        text.put(REJECTED, "The order was declined. This is an end of the flow.");
        text.put(CANCELLED, "The order was called off before any goods moved; its reservations went back to "
                + "stock. It is closed as completed two days later.");
        return Collections.unmodifiableMap(text);
    }

    private static List<Transition> build() {
        List<Transition> rows = new ArrayList<>();

        // review: a manager takes an incoming order up. From REVIEW it changes nothing, but it is allowed.
        manual(rows, OrderAction.REVIEW, false, "", CREATED, REVIEW);
        manual(rows, OrderAction.REVIEW, false, "Already under review: nothing changes", REVIEW, REVIEW);

        // accept: the delivery date is confirmed either way, the stock decides where the order lands.
        for (OrderStatus from : List.of(CREATED, REVIEW)) {
            manual(rows, ACCEPT, true, "Every line is covered by stock", from, ACKNOWLEDGED);
            manual(rows, ACCEPT, true, "Some line is not covered by stock", from, WAIT_SUPPLY);
        }

        // reserve: stock is held, so fulfillment starts. Also done by the sweep.
        // From ACKNOWLEDGED and WAIT_SUPPLY it is the way; from CREATED and REVIEW it skips them.
        for (OrderStatus from : List.of(CREATED, REVIEW, ACKNOWLEDGED, WAIT_SUPPLY)) {
            manual(rows, RESERVE, true, "At least one line could be reserved", from, IN_FULFILLMENT);
            if (from == CREATED || from == REVIEW) {
                rows.add(rows.removeLast().asShortcut());
            }
        }

        // release: nothing is held any more, so a manager decides again.
        rows.add(new Transition(IN_FULFILLMENT, REVIEW, RELEASE, Trigger.MANUAL, MANAGEMENT, true,
                "No active reservation is left and no line is past RESERVED", "all released"));

        // reject: right up to fulfillment, never once goods are in a shipment.
        for (OrderStatus from : BEFORE_DISPATCH) {
            manual(rows, REJECT, false, "No line has left the shelf", from, REJECTED);
        }

        // cancel: only while nothing has physically moved; after that the shipment is called off instead.
        for (OrderStatus from : BEFORE_DISPATCH) {
            manual(rows, CANCEL, false, "No line has left the shelf", from, CANCELLED);
        }

        // shipment progress: computed from the quantities of the lines that sit in shipments, never pushed.
        // Forward as the shipments advance, and one step back when a shipment is called off.
        // The next status is the way; skipping one is a shortcut - except past PARTIALLY_DELIVERED, which an
        // order whose last shipment arrives together with the rest never passes.
        computed(rows, IN_FULFILLMENT, false, READY_FOR_DISPATCH);
        computed(rows, IN_FULFILLMENT, true, IN_TRANSIT, PARTIALLY_DELIVERED, DELIVERED);
        computed(rows, READY_FOR_DISPATCH, false, IN_TRANSIT);
        computed(rows, READY_FOR_DISPATCH, true, PARTIALLY_DELIVERED, DELIVERED);
        computed(rows, IN_TRANSIT, false, PARTIALLY_DELIVERED, DELIVERED);
        computed(rows, PARTIALLY_DELIVERED, false, DELIVERED);
        rows.add(new Transition(READY_FOR_DISPATCH, IN_FULFILLMENT, SHIPMENT_PROGRESS, Trigger.COMPUTED,
                SHIPMENT_SIDE, false, "A shipment was called off and handed its packages back",
                "shipment cancelled"));

        // complete: the commercial end, asked on quantities and not on the status alone.
        // From PARTIALLY_DELIVERED it skips DELIVERED, which a status that is behind the quantities still passes.
        Transition completeFromDelivered = new Transition(DELIVERED, COMPLETED, COMPLETE, Trigger.MANUAL,
                MAY_COMPLETE, false, "Every line is covered by packages that arrived");
        rows.add(completeFromDelivered);
        rows.add(new Transition(PARTIALLY_DELIVERED, COMPLETED, COMPLETE, Trigger.MANUAL, MAY_COMPLETE, false,
                "Every line is covered by packages that arrived").asShortcut());

        // close: a cancelled order is filed away. Computed, so that no client offers it as a button.
        rows.add(new Transition(CANCELLED, COMPLETED, CLOSE, Trigger.COMPUTED, MANAGEMENT, true,
                "Cancelled and unchanged for two days"));

        return Collections.unmodifiableList(rows);
    }

    private static void manual(List<Transition> rows, OrderAction action, boolean automatic, String condition,
                               OrderStatus from, OrderStatus to) {
        Set<RoleEnum> actors = action == COMPLETE ? MAY_COMPLETE : MANAGEMENT;
        rows.add(new Transition(from, to, action, Trigger.MANUAL, actors, automatic, condition));
    }

    private static void computed(List<Transition> rows, OrderStatus from, boolean shortcut, OrderStatus... to) {
        for (OrderStatus target : to) {
            Transition row = new Transition(from, target, SHIPMENT_PROGRESS, Trigger.COMPUTED, SHIPMENT_SIDE, false,
                    "Follows from the quantities that sit in shipments");
            rows.add(shortcut ? row.asShortcut() : row);
        }
    }

    @Override
    public OrderStatus initialStatus() {
        return CREATED;
    }

    @Override
    public List<OrderStatus> statuses() {
        return STATUSES;
    }

    @Override
    public List<Transition> transitions() {
        return TRANSITIONS;
    }

    @Override
    public boolean allows(OrderStatus status, OrderAction action) {
        return TRANSITIONS.stream().anyMatch(row -> row.from() == status && row.action() == action);
    }

    @Override
    public Set<OrderStatus> sourcesOf(OrderAction action) {
        return TRANSITIONS.stream().filter(row -> row.action() == action).map(Transition::from)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(OrderStatus.class)));
    }

    @Override
    public Set<OrderAction> actionsFrom(OrderStatus status) {
        return TRANSITIONS.stream().filter(row -> row.from() == status).map(Transition::action)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(OrderAction.class)));
    }

    @Override
    public Set<OrderStatus> targetsOf(OrderStatus status, OrderAction action) {
        return TRANSITIONS.stream().filter(row -> row.from() == status && row.action() == action)
                .map(Transition::to)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(OrderStatus.class)));
    }

    @Override
    public boolean allowsMove(OrderStatus from, OrderStatus to) {
        return find(from, to).isPresent();
    }

    @Override
    public Optional<Transition> find(OrderStatus from, OrderStatus to) {
        return TRANSITIONS.stream().filter(row -> row.from() == from && row.to() == to).findFirst();
    }

    @Override
    public String describe(OrderStatus status) {
        return DESCRIPTIONS.get(status);
    }

    @Override
    public boolean isFinal(OrderStatus status) {
        return TRANSITIONS.stream().noneMatch(row -> row.from() == status && row.to() != status);
    }
}
