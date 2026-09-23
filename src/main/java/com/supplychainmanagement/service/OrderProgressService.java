package com.supplychainmanagement.service;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.model.enums.OrderStatus;

import java.util.Collection;

/**
 * Moves orders along their status chain on behalf of something else - today the shipment their
 * packages travel in.
 * <p>
 * Order logic, not shipment logic: which status may follow which, that an order never moves
 * backwards by accident, and that every change is published as an {@code OrderStatusChangedEvent}
 * so the audit row is written. Kept out of {@code ShipmentServiceImpl} for that reason - a second
 * caller (a returned delivery, a sweep) would otherwise copy the rules.
 * <p>
 * Called from inside the caller's transaction: an {@code AFTER_COMMIT} listener drops events
 * published without one.
 */
public interface OrderProgressService {

    /**
     * Takes the given orders to {@code target} - forwards only. An order already at or past it (a
     * second shipment reported earlier, a repeated call) and one that ended in REJECTED or CANCELLED
     * are left as they are.
     */
    void advance(Collection<Long> orderIds, OrderStatus target, Long userId);

    /**
     * The way back from READY_FOR_DISPATCH to IN_FULFILLMENT, for a dispatch that was called off.
     * An order keeps its status while any of its lines is still READY_FOR_DISPATCH - that line
     * travels in another shipment.
     */
    void takeBackFromDispatch(Collection<Long> orderIds, Long userId);

    /**
     * Writes {@code target} on the order and publishes the {@code OrderStatusChangedEvent} the audit
     * row is built from - the one place a status change is recorded. A caller that writes the status
     * itself and forgets the event leaves a hole in the history, which is what this method exists to
     * prevent.
     * <p>
     * No rule about the direction here: the caller decides whether the step is allowed - a rejection
     * and the way back from a cancelled dispatch both move against the flow. {@link #advance} is the
     * variant that only ever moves forwards. A {@code null} target and one the order already has are
     * ignored, so a write that changes nothing raises no event either.
     */
    void changeStatus(Order order, OrderStatus target, Long userId);

    /**
     * The first entry of an order's history: from no status to the one it was created with. Only the
     * event - the status itself is already written, {@code Order}'s {@code @PrePersist} sets it.
     */
    void recordCreated(Order order, Long userId);
}
