package com.supplychainmanagement.dto.reservation;

import com.supplychainmanagement.entity.Reservation;

import java.util.List;

/**
 * What the fulfillment layer hands back to the API. Deliberately carries only the reservations
 * created by this call, not the ones already active: the list of active reservations exists purely
 * for the internal status reconciliation and must not leak into a response.
 *
 * @param created reservations this call inserted - empty on a repeated call
 * @param outcome tells apart the two reasons an empty {@code created} list can have
 */
public record ReservationSummary(List<Reservation> created, ReservationOutcome outcome) {
}
