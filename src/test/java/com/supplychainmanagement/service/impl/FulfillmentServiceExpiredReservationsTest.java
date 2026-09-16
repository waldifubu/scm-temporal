package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the expiry sweep gets to work on: the orders behind the expired reservations, each once. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FulfillmentServiceExpiredReservationsTest {

    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private FulfillmentServiceImpl service;

    private Reservation expiredFor(String orderId) {
        Storehouse storehouse = new Storehouse();
        storehouse.setId(7L);
        return Reservation.active(new OrderItem(), orderId, SKU, 1, storehouse);
    }

    private Order order(Long id) {
        Order order = new Order();
        order.setId(id);
        return order;
    }

    private void expired(Reservation... reservations) {
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(List.of(reservations));
    }

    /** Two expired reservations of one order are still one order to release - and one query for all. */
    @Test
    @SuppressWarnings("unchecked")
    void asksForEachOrderOnceInOneQuery() {
        expired(expiredFor("42"), expiredFor("43"), expiredFor("42"));
        List<Order> orders = List.of(order(42L), order(43L));
        when(orderRepository.findWithOrderItemsByIdIn(anyCollection())).thenReturn(orders);

        assertThat(service.findOrdersWithExpiredReservations()).isSameAs(orders);

        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(orderRepository).findWithOrderItemsByIdIn(ids.capture());
        assertThat(ids.getValue()).containsExactly(42L, 43L);
    }

    /**
     * Loaded through the finder that fetches the lines, not findById: the sweep hands these orders to
     * releaseItems after this transaction has closed, and releaseItems walks orderItems.
     */
    @Test
    void neverLoadsTheOrdersOneByOne() {
        expired(expiredFor("42"));
        when(orderRepository.findWithOrderItemsByIdIn(anyCollection())).thenReturn(List.of(order(42L)));

        service.findOrdersWithExpiredReservations();

        verify(orderRepository, never()).findById(any());
    }

    /** The usual case for a sweep: nothing has run out, and no order query is issued at all. */
    @Test
    void returnsNothingWithoutQueryingOrdersWhenNothingHasExpired() {
        expired();

        assertThat(service.findOrdersWithExpiredReservations()).isEmpty();

        verify(orderRepository, never()).findWithOrderItemsByIdIn(anyCollection());
    }
}
