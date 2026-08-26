package com.supplychainmanagement.model.enums;

public enum ReservationStatus {
    /**
     * The reservation is currently active and the reserved items are held for the user.
     */
    ACTIVE,
    /**
     * The reservation has been released, and the reserved items are no longer held for the user.
     */
    RELEASED,
    /**
     * The reserved items have been consumed, and the reservation is no longer active.
     */
    CONSUMED
}