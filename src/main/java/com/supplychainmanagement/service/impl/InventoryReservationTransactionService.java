package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.reservation.ReservationResult;
import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryReservationTransactionService {

    private final StockRepository stockRepository;
    private final ReservationRepository reservationRepository;
    private final OrderItemRepository orderItemRepository;

    /**
     * Reserves every item it can and skips the rest, so an order whose stock is only partly
     * available still holds what is there. Reports the reservations it created itself separately
     * from the ones active for the order afterwards - a repeated call must not present what an
     * earlier one already reserved.
     * <p>
     * The idempotency guard works per order line: a line already holding a reservation is skipped,
     * one still missing is attempted. That makes a repeated call pick up where the previous one
     * stopped, and it covers the case where checkItems picks a different storehouse the second time
     * around. Per line and not per SKU, because an order may well contain the same article twice -
     * over the SKU the second of those lines was silently skipped and never reserved.
     * <p>
     * An item that cannot be reserved is skipped, not thrown on: a single unavailable line must not
     * roll back the lines that succeeded. {@code Stock.reserve} validates before it mutates, so
     * checking availability up front leaves no half-changed entity behind.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReservationResult reserve(String orderId, List<ReserveItem> items) {
        List<Reservation> active = new ArrayList<>(reservationRepository.findActive(orderId));
        List<Reservation> created = new ArrayList<>();

        // Keyed by order line, not by SKU. Over the SKU an order could hold one reservation per
        // article, so a second line of the same product was skipped and never got one - and the
        // storehouse never entered the comparison either. Rows without an order item are legacy
        // ones from before the FK and simply do not take part in the guard.
        Set<Long> reservedOrderItemIds = active.stream()
                .map(Reservation::getOrderItem)
                .filter(Objects::nonNull)
                .map(OrderItem::getId)
                .collect(Collectors.toSet());

        for (ReserveItem item : items) {
            if (reservedOrderItemIds.contains(item.orderItemId())) {
                continue;
            }

            Stock stock = stockRepository.findByStorehouseIdAndSku(item.storehouseId(), item.sku()).orElse(null);
            if (stock == null || stock.getAvailable() < item.quantity()) {
                // No stock row, or the stock went away between checkItems and here. Leave the line
                // unreserved instead of failing the whole order.
                continue;
            }

            stock.reserve(item.quantity());

            Reservation reservation = Reservation.active(
                    // A proxy is enough: writing the foreign key needs the id, not the row.
                    // getReferenceById issues no select, so the reserve path keeps its query count.
                    orderItemRepository.getReferenceById(item.orderItemId()),
                    orderId,
                    item.sku(),
                    item.quantity(),
                    stock.getStorehouse()
            );
            Hibernate.initialize(reservation.getStorehouse());

            stockRepository.save(stock);
            reservationRepository.save(reservation);
            active.add(reservation);
            created.add(reservation);
            reservedOrderItemIds.add(item.orderItemId());
        }

        return new ReservationResult(created, active);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Reservation> release(String orderId, List<ReserveItem> items) {
        List<Reservation> released = new ArrayList<>();

        for (ReserveItem item : items) {
            Stock stock = findStock(item);
            Reservation reservation = findActiveReservation(orderId, item);

            stock.release(item.quantity());
            reservation.release();

            stockRepository.save(stock);
            // A released reservation is deleted rather than merely stored as RELEASED: because of
            // the unique constraint on order_item_id the row would otherwise block any future
            // reservation for that line for good.
            reservationRepository.delete(reservation);
            released.add(reservation);
        }

        return released;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(String orderId, List<ReserveItem> items) {
        for (ReserveItem item : items) {
            Stock stock = findStock(item);
            Reservation reservation = findActiveReservation(orderId, item);

            stock.consume(item.quantity());
            reservation.consume();

            stockRepository.save(stock);
            reservationRepository.save(reservation);
        }
    }

    private Stock findStock(ReserveItem item) {
        return stockRepository.findByStorehouseIdAndSku(item.storehouseId(), item.sku())
                .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + item.sku()));
    }

    /**
     * By order line, for the same reason the guard above is: (orderId, sku, storehouseId) is not
     * unique once an order carries the same article on two lines, and the Optional behind it would
     * have broken with a NonUniqueResultException.
     */
    private Reservation findActiveReservation(String orderId, ReserveItem item) {
        return reservationRepository.findActiveByOrderItem(item.orderItemId())
                .orElseThrow(() -> new IllegalStateException(
                        "Active reservation missing for order item: " + item.orderItemId()));
    }
}
