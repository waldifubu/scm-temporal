package com.supplychainmanagement.service;

import com.supplychainmanagement.model.enums.OrderAction;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.RoleEnum;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The life of an order as a description: which statuses there are, which step may be taken from which status, where
 * it leads and who takes it. <b>The one place that knows it</b> - {@code OrderServiceImpl},
 * {@code FulfillmentServiceImpl} and {@code OrderProgressServiceImpl} ask it instead of keeping a list of their own,
 * {@code OrderProgressService.changeStatus} refuses a move it does not list, and {@code GET /orders/workflow} hands
 * the same table to a client.
 * <p>
 * <b>What it is not.</b> It decides nothing that depends on data: whether the stock covers an order (which target
 * {@code ACCEPT} takes), whether a line has left the shelf (which {@code CANCEL} refuses) or whether every quantity
 * arrived (which {@code COMPLETE} wants) stay with the services, and the table only names those as a
 * {@link Transition#condition() condition}. It also writes nothing and holds no state.
 * <p>
 * <b>Built to be moved onto Temporal.</b> Everything here is a pure function of its arguments - no database, no
 * clock, no Spring bean, no randomness - which is what workflow code has to be to replay deterministically. A status
 * is the workflow's state, an {@link OrderAction} the name of a signal or an update, and {@link #transitions()} is
 * the table a workflow would validate them against; the activities (reserving stock, picking, shipping) stay
 * ordinary services. Keep it free of anything that is not.
 */
public interface OrderWorkflow {

    /** The status a new order starts in. */
    OrderStatus initialStatus();

    /** Every status, in the order a healthy order passes them. */
    List<OrderStatus> statuses();

    /** Every step the workflow knows, in a stable order. */
    List<Transition> transitions();

    /** Whether an order in {@code status} may take {@code action}. */
    boolean allows(OrderStatus status, OrderAction action);

    /** The statuses {@code action} may be taken from. */
    Set<OrderStatus> sourcesOf(OrderAction action);

    /** What an order in {@code status} may be asked to do. */
    Set<OrderAction> actionsFrom(OrderStatus status);

    /** Where {@code action} can lead from {@code status}: none when it is refused, two for an acceptance. */
    Set<OrderStatus> targetsOf(OrderStatus status, OrderAction action);

    /** Whether some step leads from {@code from} to {@code to}, whatever it is called. */
    boolean allowsMove(OrderStatus from, OrderStatus to);

    /** The step from {@code from} to {@code to}, if there is one. */
    Optional<Transition> find(OrderStatus from, OrderStatus to);

    /** A status nothing leads out of: the order is over. */
    boolean isFinal(OrderStatus status);

    /** What the status means, in the words of the process - a constant, like everything else here. */
    String describe(OrderStatus status);

    /** How a step comes about. */
    enum Trigger {
        /** A person asks for it. */
        MANUAL,
        /** Nobody asks: it follows from the shipments of the order, or a scheduled routine takes it. */
        COMPUTED
    }

    /**
     * One step.
     *
     * @param from      the status it starts in
     * @param to        the status it ends in
     * @param action    what is done
     * @param trigger   how it comes about
     * @param actors    the roles that may take it ({@code MANUAL}) or whose work causes it ({@code COMPUTED})
     * @param automatic whether a scheduled routine takes this step as well
     * @param condition what else has to hold, in words; empty when nothing does
     * @param label     a few words for the step when it is drawn ("all released"); empty when it needs none
     * @param shortcut  a quicker route past a status that another step passes anyway ({@code RESERVE} from
     *                  {@code CREATED} straight to {@code IN_FULFILLMENT}): allowed like any other, but a drawing
     *                  leaves it out. Declared here because only the table knows which steps are the detour and
     *                  which are the way - a status that is optional ({@code REVIEW}, {@code PARTIALLY_DELIVERED})
     *                  is no reason to hide the step around it.
     */
    record Transition(OrderStatus from, OrderStatus to, OrderAction action, Trigger trigger,
                      Set<RoleEnum> actors, boolean automatic, String condition, String label, boolean shortcut) {

        public Transition(OrderStatus from, OrderStatus to, OrderAction action, Trigger trigger,
                          Set<RoleEnum> actors, boolean automatic, String condition) {
            this(from, to, action, trigger, actors, automatic, condition, "", false);
        }

        public Transition(OrderStatus from, OrderStatus to, OrderAction action, Trigger trigger,
                          Set<RoleEnum> actors, boolean automatic, String condition, String label) {
            this(from, to, action, trigger, actors, automatic, condition, label, false);
        }

        /** This step, declared a shortcut. */
        public Transition asShortcut() {
            return new Transition(from, to, action, trigger, actors, automatic, condition, label, true);
        }
    }
}
