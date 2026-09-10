package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The idempotency guard, which is the whole point of this class.
 * <p>
 * It is keyed by order line. Business rule says an order carries every article at most once, so a
 * SKU identifies a line just as well today - but that rule lives nowhere: not in
 * {@code applyOrderItems}, not in a constraint on {@code order_items}. The guard therefore keys on
 * the thing a reservation actually belongs to, and these tests pin that down so a future change
 * cannot quietly go back to the SKU.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryReservationTransactionServiceTest {

    private static final String ORDER_ID = "42";
    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");
    private static final Long STOREHOUSE_ID = 7L;

    @Mock
    private StockRepository stockRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private OrderItemRepository orderItemRepository;

    @InjectMocks
    private InventoryReservationTransactionService service;

    private final Storehouse storehouse = storehouse();

    private Storehouse storehouse() {
        Storehouse storehouse = new Storehouse();
        storehouse.setId(STOREHOUSE_ID);
        return storehouse;
    }

    private OrderItem line(Long id, int quantity) {
        Product product = new Product();
        product.setArticleNo(1001L);
        product.setSku(SKU);

        Order order = new Order();
        order.setId(42L);

        OrderItem item = new OrderItem();
        item.setId(id);
        item.setProduct(product);
        item.setQuantity(quantity);
        item.setOrder(order);
        return item;
    }

    private ReserveItem reserveItem(Long orderItemId, int quantity) {
        return new ReserveItem(orderItemId, SKU, quantity, STOREHOUSE_ID);
    }

    /** One stock row for the shared SKU, deep enough for both lines. */
    private void stockAvailable(int onHand) {
        Stock stock = new Stock();
        stock.setSku(SKU);
        stock.setOnHand(onHand);
        stock.setReserved(0);
        stock.setStorehouse(storehouse);

        when(stockRepository.findByStorehouseIdAndSku(STOREHOUSE_ID, SKU)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(call -> call.getArgument(0));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(call -> call.getArgument(0));
    }

    /**
     * Two lines, one article, one storehouse - the case the business rule forbids and nothing
     * prevents. Keyed by SKU the second line hits the guard and is dropped without a word; keyed by
     * line both are reserved. Not a scenario to expect, but the one that shows what the guard is
     * actually comparing.
     */
    @Test
    void reservesEveryLineSeparatelyEvenWhenTheyShareAnArticle() {
        OrderItem first = line(11L, 3);
        OrderItem second = line(12L, 2);

        when(reservationRepository.findActive(ORDER_ID)).thenReturn(List.of());
        when(orderItemRepository.getReferenceById(11L)).thenReturn(first);
        when(orderItemRepository.getReferenceById(12L)).thenReturn(second);
        stockAvailable(10);

        var result = service.reserve(ORDER_ID, List.of(reserveItem(11L, 3), reserveItem(12L, 2)));

        assertThat(result.created()).hasSize(2);
        assertThat(result.created())
                .extracting(reservation -> reservation.getOrderItem().getId())
                .containsExactly(11L, 12L);
        assertThat(result.created()).extracting(Reservation::getQuantity).containsExactly(3, 2);
    }

    /** The everyday case: a repeated call must not reserve a line that already holds one. */
    @Test
    void skipsALineThatAlreadyHoldsAReservation() {
        OrderItem alreadyHeld = line(11L, 3);
        OrderItem outstanding = line(12L, 2);

        Reservation existing = Reservation.active(alreadyHeld, ORDER_ID, SKU, 3, storehouse);
        when(reservationRepository.findActive(ORDER_ID)).thenReturn(List.of(existing));
        when(orderItemRepository.getReferenceById(12L)).thenReturn(outstanding);
        stockAvailable(10);

        var result = service.reserve(ORDER_ID, List.of(reserveItem(11L, 3), reserveItem(12L, 2)));

        assertThat(result.created())
                .extracting(reservation -> reservation.getOrderItem().getId())
                .containsExactly(12L);
        assertThat(result.active()).hasSize(2);
    }

    /**
     * A legacy row from before order_item_id existed carries no line. It must not take part in the
     * guard, and above all must not throw - the sweep and every repeat call walk over these.
     */
    @Test
    void ignoresReservationsWithoutAnOrderItem() {
        Reservation legacy = Reservation.active(line(11L, 3), ORDER_ID, SKU, 3, storehouse);
        legacy.setOrderItem(null);

        when(reservationRepository.findActive(ORDER_ID)).thenReturn(List.of(legacy));
        when(orderItemRepository.getReferenceById(11L)).thenReturn(line(11L, 3));
        stockAvailable(10);

        var result = service.reserve(ORDER_ID, List.of(reserveItem(11L, 3)));

        assertThat(result.created()).hasSize(1);
    }

    /** Unchanged: a line the stock cannot cover is skipped, not thrown on. */
    @Test
    void skipsALineTheStockCannotCover() {
        when(reservationRepository.findActive(ORDER_ID)).thenReturn(List.of());
        when(orderItemRepository.getReferenceById(11L)).thenReturn(line(11L, 99));
        stockAvailable(5);

        var result = service.reserve(ORDER_ID, List.of(reserveItem(11L, 99)));

        assertThat(result.created()).isEmpty();
    }
}
