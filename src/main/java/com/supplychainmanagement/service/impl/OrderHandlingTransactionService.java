package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The transaction boundary for picking a single order line, on its own bean for a reason: picking a
 * whole order loops over its lines, and a private method called from inside
 * {@link OrderHandlingServiceImpl} would go around the proxy and be transactional in name only -
 * the same trap {@code InventoryServiceImpl} / {@code InventoryReservationTransactionService}
 * already work around.
 * <p>
 * The boundary sits at the <em>line</em>, not at the order, and that is deliberate. The stock side
 * commits on its own regardless ({@code consumeWithRetry} delegates to a REQUIRES_NEW transaction),
 * so wrapping the whole loop would let a failure on line three roll back the line statuses of lines
 * one and two while their stock stays consumed - a reservation reading ACTIVE over stock that is
 * already gone, and a second attempt consuming it twice. Per line, a failure leaves the lines
 * before it correctly and completely picked.
 */
@Service
@RequiredArgsConstructor
public class OrderHandlingTransactionService {

    private final OrderItemRepository orderItemRepository;
    private final InventoryService inventoryService;

    /**
     * Picks one reservation: the line item goes to PICKING, the reserved stock is consumed, the
     * reservation becomes CONSUMED, and only then does the line item reach PICKED.
     * <p>
     * The reservation is written exactly once, by consume in its own REQUIRES_NEW transaction - never
     * again in here. This transaction took its snapshot before consume committed, and MariaDB
     * (innodb_snapshot_isolation, on by default since 11.6) refuses to update a row that changed after
     * the snapshot: "Record has changed since last read in table 'reservation'".
     * <p>
     * The order is handed in rather than read off {@code reservation.getOrderItem().getOrder()}:
     * picking a whole order already has it, and every line would otherwise reach for it again.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PickingOrderDto pick(Order order, Reservation reservation) {
        OrderItem orderItem = orderItemValidation(order, reservation);

        orderItem.setFulfillmentStatus(FulfillmentStatus.PICKING);
        OrderItem savedOrderItem = orderItemRepository.save(orderItem);

        List<Reservation> consumed;
        try {
            consumed = inventoryService.consumeWithRetry(order.getId(), List.of(new ReserveItem(savedOrderItem.getId(), reservation.getSku(), reservation.getQuantity(), reservation.getStorehouse().getId())));
        } catch (Exception e) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Failed to consume items for reservation " + reservation.getId() + ": " + e.getMessage());
        }

        // consume skips a line it cannot consume - no stock row, or no active reservation left for
        // it - instead of throwing. Carrying on would mark the reservation CONSUMED and the line
        // PICKED although no stock was taken out. Thrown outside the try so the message is not
        // wrapped a second time; the rollback also takes the line back out of PICKING.
        if (consumed.isEmpty()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Nothing consumed for reservation " + reservation.getId()
                    + ": stock or active reservation missing, order item " + savedOrderItem.getId() + " not picked");
        }

        // Deliberately not reservation.setShipmentPackageStatus(CONSUMED) + save: consume has already written CONSUMED
        // and committed. A second write from this transaction is the one MariaDB rejects, see above.
        // The reservation instance in hand therefore still reads ACTIVE in memory - it is only used
        // for the response below, which does not carry the reservation status.

        // Only after the reservation is really consumed, so a line never reads as PICKED while its
        // stock is still reserved.
        savedOrderItem.setFulfillmentStatus(FulfillmentStatus.PICKED);
        orderItemRepository.save(savedOrderItem);

        // Mapped in here, while the transaction is open, so the LAZY product and storehouse are
        // resolved before the result leaves the boundary.
        return PickingOrderDto.of(order, reservation);
    }

    /**
     * Takes a pick back: the line goes from PICKED to WAITING, the quantity that was taken off the shelf goes
     * onto it again, and the CONSUMED reservation is deleted at the end.
     * <p>
     * Same boundary as {@link #pick}, for the same reason: the stock side commits in a transaction of its own
     * ({@code restoreWithRetry} is REQUIRES_NEW), so this one is the line. The line is set first and the stock
     * handed back after, so a failure on the stock side rolls the line back with it; and the reservation is
     * only touched by that inner transaction, never from here - this one took its snapshot before the inner one
     * commits, which MariaDB (innodb_snapshot_isolation) would refuse a write on.
     * <p>
     * Only a line that is still PICKED can be taken back. Once any of it is in a package (PACKING, PACKED) or
     * further on, the goods are no longer where a pick put them, and it is a 409.
     * <p>
     * The order is not touched: it stays IN_FULFILLMENT, and the line - WAITING again, without a reservation -
     * is reserved anew by the next {@code reserveItems} (the sweep looks at IN_FULFILLMENT orders).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PickingOrderDto unpick(Order order, Reservation reservation) {
        OrderItem orderItem = reservation.getOrderItem();
        if (orderItem == null) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Reservation " + reservation.getId() + " belongs to no order line");
        }
        if (!orderItem.getOrder().getId().equals(order.getId())) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Reservation " + reservation.getId() + " does not belong to order " + order.getId());
        }
        if (orderItem.getFulfillmentStatus() != FulfillmentStatus.PICKED) {
            throw new APIException(HttpStatus.CONFLICT, "Order item " + orderItem.getId() + " is "
                    + orderItem.getFulfillmentStatus() + ", only a PICKED line can be taken back");
        }

        orderItem.setFulfillmentStatus(FulfillmentStatus.WAITING);
        orderItemRepository.save(orderItem);

        // Mapped here, while the reservation still exists and its LAZY product and storehouse can be resolved;
        // the row shows the line as it is after the pick is gone.
        PickingOrderDto result = PickingOrderDto.of(order, reservation);

        List<Reservation> restored;
        try {
            restored = inventoryService.restoreWithRetry(order.getId(), List.of(new ReserveItem(orderItem.getId(), reservation.getSku(), reservation.getQuantity(), reservation.getStorehouse().getId())));
        } catch (Exception e) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Failed to take back reservation " + reservation.getId() + ": " + e.getMessage());
        }
        // Skipped instead of thrown on - no stock row, no consumed reservation. Carrying on would leave the line
        // WAITING although nothing went back onto the shelf; the rollback takes it back to PICKED.
        if (restored.isEmpty()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Nothing taken back for reservation " + reservation.getId()
                    + ": stock row or consumed reservation missing, order item " + orderItem.getId() + " stays PICKED");
        }

        return result;
    }

    private static @NonNull OrderItem orderItemValidation(Order order, Reservation reservation) {
        OrderItem orderItem = reservation.getOrderItem();
        // Check orderItem is in Order
        if (!orderItem.getOrder().getId().equals(order.getId())) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Reservation " + reservation.getId() + " does not belong to order " + order.getId());
        }
        if (orderItem.getFulfillmentStatus() == FulfillmentStatus.PICKED) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item is already in PICKED status, cannot move to PICKED");
        }
        if (orderItem.getFulfillmentStatus() != FulfillmentStatus.RESERVED) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item is not in RESERVED status, cannot move to PICKED");
        }
        return orderItem;
    }
}
