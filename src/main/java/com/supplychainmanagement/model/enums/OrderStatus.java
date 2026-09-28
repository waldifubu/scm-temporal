package com.supplychainmanagement.model.enums;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum OrderStatus {
    @JsonEnumDefaultValue
    CREATED, // Initial status
    ACKNOWLEDGED, // Order is opened/seen by manager users after creating
    REVIEW, // Checking if order is possible, run inventory check
    APPROVED, // All needed products are in stock. Order is approved, process will go on
    REJECTED, // Order is rejected by manager
    WAIT_SUPPLY, // Order is waiting for supply from supplier, not enough stock in warehouse
    IN_FULFILLMENT, // Order is in preparation, may be in multiple steps, like picking, packing, etc.
    READY_FOR_DISPATCH, // Warehouse tells: Order is ready for distributor,
    IN_TRANSIT, // Distributor tells order is on the way
    PARTIALLY_DELIVERED, // Part of the order has arrived, the rest travels in another shipment
    DELIVERED, // Distributor tells delivery to customer is done
    CANCELLED, // Manager/Customer cancels the order
    COMPLETED, // Customer/Manager confirms order is completed, last status
}
