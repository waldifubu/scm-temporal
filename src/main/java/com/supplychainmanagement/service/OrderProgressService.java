package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.order.UndeliveredLine;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.model.enums.OrderStatus;

import java.util.Collection;
import java.util.List;

/**
 * Moves orders along their status chain on behalf of something else - today the shipment their
 * packages travel in.
 * <p>
 * Order logic, not shipment logic: which status follows from what has actually happened, and that
 * every change is published as an {@code OrderStatusChangedEvent} so the audit row is written. Kept
 * out of {@code ShipmentServiceImpl} for that reason - a second caller (a returned delivery, a
 * sweep) would otherwise copy the rules.
 * <p>
 * Called from inside the caller's transaction: an {@code AFTER_COMMIT} listener drops events
 * published without one.
 */
public interface OrderProgressService {

    /**
     * Works out where each order really stands and writes that status - the shipment side never names
     * a target any more, it only says which orders were touched.
     * <p>
     * The status follows from the <em>quantities</em> of the order's lines that sit in shipments, not
     * from the step that was just reported. That is the whole point: an order is spread over as many
     * shipments as its packages need, so the shipment reporting in says nothing about the order as a
     * whole. Pushing a fixed target was what let the first carrier to report call a whole order
     * delivered.
     * <p>
     * Only ever writes within {@code IN_FULFILLMENT … DELIVERED}, and only for an order already in
     * that range. Everything before it belongs to the order side - {@code CREATED} through
     * {@code APPROVED} and {@code WAIT_SUPPLY}, which is about stock and not about shipments - and
     * {@code REJECTED}, {@code CANCELLED} and {@code COMPLETED} are ends, not steps. An order outside
     * the range is skipped, deliberately and not as a side effect.
     * <p>
     * Both directions: a cancelled shipment takes its packages out, the coverage drops, and the order
     * falls back by itself. There is no separate way back any more.
     */
    void recompute(Collection<Long> orderIds, Long userId);

    /**
     * The lines of the order that have not fully arrived, each with what was ordered against what
     * came. An empty list means every line is completely delivered - and an order without lines
     * yields one too, so a caller that can see such an order has to refuse it before asking.
     * <p>
     * The order has to arrive with its {@code orderItems} fetched; the quantities come in one query.
     */
    List<UndeliveredLine> undeliveredLines(Order order);

    /**
     * Writes {@code target} on the order and publishes the {@code OrderStatusChangedEvent} the audit
     * row is built from - the one place a status change is recorded. A caller that writes the status
     * itself and forgets the event leaves a hole in the history, which is what this method exists to
     * prevent.
     * <p>
     * No rule about the direction here: the caller decides whether the step is allowed - a rejection
     * and a fallback both move against the flow. {@link #recompute} is the variant that works the
     * status out for itself. A {@code null} target and one the order already has are ignored, so a
     * write that changes nothing raises no event either.
     */
    void changeStatus(Order order, OrderStatus target, Long userId);

    /**
     * The first entry of an order's history: from no status to the one it was created with. Only the
     * event - the status itself is already written, {@code Order}'s {@code @PrePersist} sets it.
     */
    void recordCreated(Order order, Long userId);
}
