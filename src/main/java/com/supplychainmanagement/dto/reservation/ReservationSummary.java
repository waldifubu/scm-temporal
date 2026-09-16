package com.supplychainmanagement.dto.reservation;

import java.util.List;

/**
 * What the fulfillment layer hands back to the API. Deliberately carries only the reservations
 * created by this call, not the ones already active: the list of active reservations exists purely
 * for the internal status reconciliation and must not leak into a response.
 *
 * @param created reservations this call inserted - empty on a repeated call. DTOs rather than
 *                entities: they come out of the REQUIRES_NEW reserve transaction, whose session is
 *                closed by the time anyone serializes or logs them, see {@link ReservationDto}
 * @param outcome tells apart the two reasons an empty {@code created} list can have
 */
public record ReservationSummary(List<ReservationDto> created, ReservationOutcome outcome) {
}
