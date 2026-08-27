package com.supplychainmanagement.dto.reservation;

import com.supplychainmanagement.entity.Reservation;

import java.util.List;

public record ReservationResult(List<Reservation> reservations, boolean created) {
}
