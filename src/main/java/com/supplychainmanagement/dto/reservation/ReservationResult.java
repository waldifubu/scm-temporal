package com.supplychainmanagement.dto.reservation;

import com.supplychainmanagement.entity.Reservation;

import java.util.List;

/**
 * Outcome of a single reservation attempt, split by origin.
 *
 * @param created reservations this very call inserted. Only these are reported back to the client -
 *                a repeated call must not present what an earlier one already reserved.
 * @param active  every reservation active for the order afterwards, previously existing ones
 *                included. Internal only: the fulfillment layer reconciles the order and line item
 *                statuses against it, which has to work even when this call created nothing.
 */
public record ReservationResult(List<Reservation> created, List<Reservation> active) {
}
