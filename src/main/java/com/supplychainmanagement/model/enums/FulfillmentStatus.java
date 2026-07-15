package com.supplychainmanagement.model.enums;

public enum FulfillmentStatus {
    /**
     * Fulfillment process has not started yet.
     * Order is waiting for warehouse processing.
     */
    WAITING,

    /**
     * System is checking and reserving required inventory items.
     */
    RESERVING,

    /**
     * All required items have been successfully reserved
     * and are guaranteed for this order.
     */
    RESERVED,

    /**
     * Warehouse staff is currently picking items
     * from storage locations.
     */
    PICKING,

    /**
     * All required items have been picked
     * and are ready for the next fulfillment step.
     */
    PICKED,

    /**
     * Items are currently being packed
     * for shipment preparation.
     */
    PACKING,

    /**
     * Order items have been completely packed
     * and the package is ready for dispatch processing.
     */
    PACKED,

    /**
     * Fulfillment is completed.
     * The order is ready to be handed over to the carrier.
     */
    READY_FOR_DISPATCH,
}
