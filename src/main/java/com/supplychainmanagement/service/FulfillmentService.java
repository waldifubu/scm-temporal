package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.reservation.ReservationSummary;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Reservation;

import java.util.List;

public interface FulfillmentService {

    ReservationSummary reserveItems(Order order, String username);

    /**
     * Releases everything the order still holds.
     *
     * @param username the acting user, resolved for the audit trail the same way reserveItems does
     * @return the reservations that were actually released
     */
    List<Reservation> releaseItems(Order order, String username);

    /**
     * The orders holding at least one reservation whose {@code expiresAt} has passed, each once and
     * with its lines loaded - all a sweep needs to call {@link #releaseItems(Order, String)} per
     * order.
     */
    List<Order> findOrdersWithExpiredReservations();

    /**
     * The lines of the order whose goods have already left the shelf - anything past RESERVED, so
     * PICKING through READY_FOR_DISPATCH. Empty means nothing has physically moved and every
     * reservation the order holds can simply be handed back.
     * <p>
     * A picked line cannot be handed back: its reservation is CONSUMED, the stock is gone, and
     * booking it in again is an operation this application does not have. That is what makes this
     * the boundary for cancelling an order. CANCELLED does not count - nothing is held there either.
     * <p>
     * The order has to arrive with its {@code orderItems} fetched.
     */
    List<OrderItem> linesPastReservation(Order order);

    /**
     * The reservations that are still active for the given order, i.e. {@code expiresAt} is in the
     * future.
     */
    List<Reservation> findActiveReservations(Order order);

    List<Reservation> findConsumedReservations(Order order);

    /**
     * Deletes one consumed reservation - the row that keeps its order line from ever holding a
     * reservation again. Bookkeeping only: no status is touched and no event is published, so it
     * takes no acting user. It used to take one for a {@code revertOrderStatus} call that could
     * never fire on the orders this is run for.
     */
    Reservation deleteReservation(Reservation reservation);
}
