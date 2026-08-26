package com.supplychainmanagement.model.enums;

public enum RequestStatus {
    OPEN(0), // Just created
    APPROVED(1), // Supplier tells request is approved
    IN_TRANSIT(2), // Supplier tells request is on the way
    STORAGE(3), // Means item is received (e.g. by truck delivery)
    ASSEMBLED(4); // Part is assembled in a product

    RequestStatus(int i) {

    }
}
