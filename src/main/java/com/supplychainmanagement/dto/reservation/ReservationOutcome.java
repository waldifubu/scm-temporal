package com.supplychainmanagement.dto.reservation;

/**
 * What a reservation call achieved. Since a repeated call reports only what it created itself, an
 * empty response body alone would no longer say whether the order is done or still waiting for
 * stock - this distinction carries that information.
 */
public enum ReservationOutcome {
    /** At least one reservation was newly created. */
    CREATED,
    /** Nothing new: every line of the order is reserved or further along in fulfillment. */
    COMPLETE,
    /** Nothing new, but lines are still outstanding. Worth calling again once stock arrives. */
    PENDING
}
