package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.reservation.ReservationResult;
import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryReservationTransactionService {

    private final StockRepository stockRepository;
    private final ReservationRepository reservationRepository;
    private final OrderItemRepository orderItemRepository;

    private static String keyOf(ReserveItem item) {
        return item.storehouseId() + ":" + item.sku();
    }

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
    public ReservationResult reserve(Long orderId, List<ReserveItem> items) {
        // The stock rows first, before any other read of this transaction: see lockStocks.
        Map<String, Stock> stocks = lockStocks(items);
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

            Stock stock = stocks.get(keyOf(item));
            if (stock == null || stock.getAvailable() < item.quantity()) {
                // No stock row, or the stock went away between checkItems and here. Leave the line
                // unreserved instead of failing the whole order.
                continue;
            }

            Reservation reservation;

            try {
                stock.reserve(item.quantity());
                reservation = Reservation.active(
                        // A proxy is enough: writing the foreign key needs the id, not the row.
                        // getReferenceById issues no select, so the reserve path keeps its query count.
                        orderItemRepository.getReferenceById(item.orderItemId()),
                        item.sku(),
                        item.quantity(),
                        stock.getStorehouse()
                );
                Hibernate.initialize(reservation.getStorehouse());
            } catch (IllegalArgumentException | IllegalStateException ile) {
                log.warn(ile.getMessage() + " - skipping reservation for order item: " + item.orderItemId());
                continue;
            }

            stockRepository.save(stock);
            reservationRepository.save(reservation);
            active.add(reservation);
            created.add(reservation);
            reservedOrderItemIds.add(item.orderItemId());
        }

        return new ReservationResult(created, active);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Reservation> release(Long orderId, List<ReserveItem> items) {
        List<Reservation> released = new ArrayList<>();
        Map<String, Stock> stocks = lockStocks(items);

        Stock stock;
        Reservation reservation;

        for (ReserveItem item : items) {
            try {
                stock = findStock(stocks, item);
                reservation = findActiveReservation(orderId, item);

                stock.release(item.quantity());
                reservation.release();

                stockRepository.save(stock);
                // A released reservation is deleted rather than merely stored as RELEASED: because of
                // the unique constraint on order_item_id the row would otherwise block any future
                // reservation for that line for good.
                reservationRepository.delete(reservation);
            } catch (IllegalArgumentException | IllegalStateException ile) {
                log.warn(ile.getMessage() + " - skipping release for order item: " + item.orderItemId());
                continue;
            }

            released.add(reservation);
        }

        return released;
    }

    /**
     * Returns the reservations this call set to CONSUMED. A line without stock or without an active
     * reservation is skipped, so the list can be shorter than {@code items} - it is the only way for
     * a caller to tell a consumed line from a skipped one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Reservation> consume(Long orderId, List<ReserveItem> items) {
        List<Reservation> consumed = new ArrayList<>();
        Map<String, Stock> stocks = lockStocks(items);

        for (ReserveItem item : items) {

            Stock stock;
            Reservation reservation;
            try {
                stock = findStock(stocks, item);
                reservation = findActiveReservation(orderId, item);

                stock.consume(item.quantity());
                reservation.consume();
                stockRepository.save(stock);
                reservationRepository.save(reservation);

            } catch (IllegalArgumentException | IllegalStateException ile) {
                log.warn(ile.getMessage() + " - skipping consumption for order item: " + item.orderItemId());
                continue;
            }

            consumed.add(reservation);
        }

        return consumed;
    }

    /**
     * The reverse of {@link #consume}: each line's quantity goes back onto its stock row and the
     * {@code CONSUMED} reservation is deleted - deleted, not kept, for the reason a released one is: its row
     * holds the line's one slot ({@code uk_reservation_order_item}), so the line could never be reserved
     * again while it was there.
     * <p>
     * A line without a stock row or without a consumed reservation is skipped, like in {@code consume}; the
     * returned list is how a caller tells a restored line from a skipped one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Reservation> restore(Long orderId, List<ReserveItem> items) {
        List<Reservation> restored = new ArrayList<>();
        Map<String, Stock> stocks = lockStocks(items);

        for (ReserveItem item : items) {
            Reservation reservation;
            try {
                Stock stock = findStock(stocks, item);
                reservation = reservationRepository
                        .findByOrderItemIdAndStatus(item.orderItemId(), ReservationStatus.CONSUMED)
                        .orElseThrow(() -> new IllegalStateException(
                                "Consumed reservation missing for order item: " + item.orderItemId()));

                stock.restore(item.quantity());
                stockRepository.save(stock);
                reservationRepository.delete(reservation);
            } catch (IllegalArgumentException | IllegalStateException ile) {
                log.warn(ile.getMessage() + " - skipping restore for order item: " + item.orderItemId());
                continue;
            }

            restored.add(reservation);
        }

        return restored;
    }

    private Stock findStock(Map<String, Stock> locked, ReserveItem item) {
        Stock stock = locked.get(keyOf(item));
        if (stock == null) {
            throw new IllegalArgumentException("Stock not found: " + item.sku());
        }
        return stock;
    }

    /**
     * Locks the stock row of every item for the rest of this transaction ({@code PESSIMISTIC_WRITE}), and returns
     * them by storehouse and SKU. A row that does not exist is simply not in the map.
     * <p>
     * Every booking reads {@code onHand}/{@code reserved} and writes it back; two of them on one row would compute
     * from the same value, and {@code @Version} turns that into an {@code OptimisticLockException} and a retry. The
     * lock makes the second one wait instead.
     * <p>
     * <strong>Ascending storehouse and SKU, whatever order the items come in</strong>: two orders reserving the same
     * two articles in opposite order would otherwise each hold one row and wait for the other - a deadlock.
     * <strong>And first in the transaction</strong>: MariaDB runs with {@code innodb_snapshot_isolation}, and a locking
     * read of a row that changed after this transaction took its snapshot (the first plain read) is refused with
     * "Record has changed since last read" instead of waiting.
     */
    private Map<String, Stock> lockStocks(List<ReserveItem> items) {
        Map<String, Stock> locked = new HashMap<>();
        items.stream()
                .sorted(Comparator.comparing(ReserveItem::storehouseId, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ReserveItem::sku, Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(item -> {
                    String key = keyOf(item);
                    if (!locked.containsKey(key)) {
                        stockRepository.findForUpdateByStorehouseIdAndSku(item.storehouseId(), item.sku())
                                .ifPresent(stock -> locked.put(key, stock));
                    }
                });
        return locked;
    }

    /**
     * By order line, for the same reason the guard above is: (order, sku, storehouse) is not unique
     * once an order carries the same article on two lines, and the Optional behind it would have
     * broken with a NonUniqueResultException.
     */
    private Reservation findActiveReservation(Long orderId, ReserveItem item) {
        return reservationRepository.findActiveByOrderItem(item.orderItemId())
                .orElseThrow(() -> new IllegalStateException(
                        "Active reservation missing for order item: " + item.orderItemId()));
    }
}
