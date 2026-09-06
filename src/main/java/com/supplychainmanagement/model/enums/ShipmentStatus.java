package com.supplychainmanagement.model.enums;

public enum ShipmentStatus {
    CREATED, // Shipment has been created but not yet processed.

    READY, // Shipment is ready for dispatch.

    DISPATCH_REQUESTED, // Shipment dispatch has been requested.

    ACCEPTED, // Shipment has been accepted for dispatch.

    IN_TRANSIT, // Shipment is currently in transit.

    DELIVERED, // Shipment has been delivered to the destination.

    CANCELLED // Shipment has been cancelled.
}
