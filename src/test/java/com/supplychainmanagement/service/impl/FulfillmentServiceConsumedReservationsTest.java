package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.service.OrderProgressService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The consumed reservations of one order - what tryToDelete works on. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FulfillmentServiceConsumedReservationsTest {

    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private OrderProgressService orderProgress;

    @InjectMocks
    private FulfillmentServiceImpl service;

    /**
     * Asked per order in the database. The old way - the first 100 CONSUMED rows of all orders,
     * filtered in memory - lost every reservation of an order beyond those 100.
     */
    @Test
    void asksTheDatabaseForTheConsumedReservationsOfThisOrder() {
        Order order = new Order();
        order.setId(42L);

        Storehouse storehouse = new Storehouse();
        storehouse.setId(7L);
        Reservation consumed = Reservation.active(new OrderItem(), SKU, 1, storehouse);
        when(reservationRepository.findByOrderItemOrderIdAndStatus(42L, ReservationStatus.CONSUMED))
                .thenReturn(List.of(consumed));

        assertThat(service.findConsumedReservations(order)).containsExactly(consumed);
        verify(reservationRepository, never()).findAllByStatus(any(), any());
    }
}
