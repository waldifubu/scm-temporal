package com.supplychainmanagement.service.business;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.service.FulfillmentService;
import com.supplychainmanagement.service.OrderService;
import com.supplychainmanagement.service.ProductionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cleanup sweep for consumed reservations - the routine that could not run at all.
 * <p>
 * Three things were wrong with it, and all three are about what it measures and what it touches: the
 * age came from {@code expiresAt}, an hour after reserving, so a line picked weeks later looked old
 * the moment it was picked; the delete then called {@code revertOrderStatus}, which can only act on
 * IN_FULFILLMENT while this sweep works on READY_FOR_DISPATCH orders; and reaching the order for
 * that call happened after the delete, in a scheduler with no session.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutomaticReservationServiceDeleteTest {

    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");

    @Mock
    private FulfillmentService fulfillmentService;
    @Mock
    private OrderService orderService;
    @Mock
    private ProductionService productionService;

    @InjectMocks
    private AutomaticReservationService service;

    /** An order the sweep picks up, holding the given consumed reservations. */
    private Order orderHolding(Reservation... reservations) {
        Order order = new Order();
        order.setId(42L);
        order.setOrderNo(1042L);
        order.setStatus(OrderStatus.READY_FOR_DISPATCH);

        Page<Order> page = new PageImpl<>(List.of(order));
        when(orderService.findAllByStatus(any(OrderStatus.class), any(Pageable.class))).thenReturn(page);
        when(fulfillmentService.findConsumedReservations(order)).thenReturn(List.of(reservations));
        return order;
    }

    /**
     * A consumed reservation, with the two timestamps set as given. Built through the factory and
     * {@code consume()} so {@code consumedAt} is stamped the way production stamps it, then
     * overridden where the test needs a specific age.
     */
    private static Reservation consumed(LocalDateTime consumedAt, LocalDateTime expiresAt) {
        Order order = new Order();
        order.setId(42L);

        OrderItem line = new OrderItem();
        line.setId(11L);
        line.setOrder(order);

        Storehouse storehouse = new Storehouse();
        storehouse.setId(1L);

        Reservation reservation = Reservation.active(line, SKU, 5, storehouse);
        reservation.consume();
        reservation.setId(7L);
        reservation.setConsumedAt(consumedAt);
        reservation.setExpiresAt(expiresAt);
        return reservation;
    }

    private static LocalDateTime daysAgo(long days) {
        return LocalDateTime.now().minusDays(days);
    }

    /** consume() stamps the moment the goods left the shelf - that is what the age is measured from. */
    @Test
    void stampsTheConsumptionTime() {
        Reservation reservation = consumed(null, null);
        reservation.setConsumedAt(null);

        Reservation fresh = consumed(daysAgo(0), null);

        assertThat(fresh.getConsumedAt()).isNotNull();
        assertThat(fresh.consumedOrExpiredAt()).isEqualTo(fresh.getConsumedAt());
    }

    /** Long consumed: deleted, and nothing else happens to the order. */
    @Test
    void deletesAReservationConsumedLongAgo() {
        Reservation old = consumed(daysAgo(5), daysAgo(30));
        orderHolding(old);

        service.tryToDelete();

        verify(fulfillmentService).deleteReservation(old);
    }

    /**
     * Just consumed: kept. This is the case the old sweep got wrong - it measured expiresAt, which is
     * an hour after reserving, so a line reserved weeks ago and picked a minute ago was deleted on
     * the spot.
     */
    @Test
    void keepsAFreshlyConsumedReservationEvenIfItWasReservedLongAgo() {
        Reservation justPicked = consumed(daysAgo(0), daysAgo(30));
        orderHolding(justPicked);

        service.tryToDelete();

        verify(fulfillmentService, never()).deleteReservation(any());
    }

    /** A row consumed before the column existed falls back to expiresAt rather than being skipped. */
    @Test
    void fallsBackToTheExpiryForARowWithoutAConsumptionTime() {
        Reservation legacy = consumed(null, daysAgo(30));
        orderHolding(legacy);

        service.tryToDelete();

        verify(fulfillmentService).deleteReservation(legacy);
    }

    /** Neither timestamp means unknown age, not old age - and it must not throw. */
    @Test
    void leavesARowWithoutAnyTimestampAlone() {
        Reservation undated = consumed(null, null);
        orderHolding(undated);

        service.tryToDelete();

        verify(fulfillmentService, never()).deleteReservation(any());
    }

    /**
     * One bad row does not stop the sweep: the next reservation is still looked at, and the next run
     * sees the failed one again.
     */
    @Test
    void carriesOnAfterAFailedDelete() {
        Reservation first = consumed(daysAgo(5), daysAgo(30));
        Reservation second = consumed(daysAgo(6), daysAgo(30));
        second.setId(8L);
        orderHolding(first, second);
        when(fulfillmentService.deleteReservation(first)).thenThrow(new RuntimeException("row is gone"));

        service.tryToDelete();

        verify(fulfillmentService).deleteReservation(second);
    }

    /** It works off READY_FOR_DISPATCH orders - the ones whose lines are picked and packed. */
    @Test
    void looksAtReadyForDispatchOrders() {
        orderHolding();

        service.tryToDelete();

        // The first 100 of that status, like every other routine in this class.
        verify(orderService).findAllByStatus(eq(OrderStatus.READY_FOR_DISPATCH),
                argThat((Pageable pageable) -> pageable.getPageNumber() == 0 && pageable.getPageSize() == 100));
    }
}
