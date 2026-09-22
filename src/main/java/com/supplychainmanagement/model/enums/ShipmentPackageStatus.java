package com.supplychainmanagement.model.enums;

public enum ShipmentPackageStatus {
    OPEN, // Package is created but not yet filled with items
    PACKED, // Package is filled and ready for dispatch
    DISPATCHED // Package has been dispatched to the customer
}
