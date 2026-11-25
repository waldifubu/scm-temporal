package com.supplychainmanagement.model.enums;

/**
 * What can be done to an order, as the workflow knows it: the name of the step, not the endpoint that happens to
 * carry it. {@code OrderWorkflow} says which action may be taken from which status and where it leads.
 * <p>
 * The names are meant to outlive the REST API: they are what a signal or an update of a workflow engine would be
 * called.
 */
public enum OrderAction {
    /** A manager takes an incoming order up and has not answered the customer yet. */
    REVIEW,
    /** The order is accepted and a delivery date is confirmed; where it lands depends on the stock. */
    ACCEPT,
    /** The order is turned down. */
    REJECT,
    /** The order is called off while nothing has physically moved. */
    CANCEL,
    /** Stock is held for the order's lines - by hand or by the sweep that does it on its own. */
    RESERVE,
    /** Every reservation the order held is gone again, so it goes back to a manager. */
    RELEASE,
    /** The shipments the order's packages travel in moved on; where the order stands follows from them. */
    SHIPMENT_PROGRESS,
    /** The customer or a manager confirms that everything arrived. */
    COMPLETE,
    /** A cancelled order is filed away as completed - a scheduled routine does it, nobody asks for it. */
    CLOSE
}
