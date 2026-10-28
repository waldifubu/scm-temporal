package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Which reservation a take-back means: a picked one, and only that. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderHandlingServiceUnpickTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private OrderHandlingTransactionService transactionService;

    @InjectMocks
    private OrderHandlingServiceImpl service;

    @Test
    void handsAConsumedReservationAndItsOrderToTheTransactionService() {
        Order order = new Order();
        order.setId(42L);
        OrderItem line = new OrderItem();
        line.setOrder(order);
        Reservation reservation = mock(Reservation.class);
        when(reservation.getOrderItem()).thenReturn(line);
        when(reservationRepository.findByIdAndStatus(25L, ReservationStatus.CONSUMED)).thenReturn(Optional.of(reservation));
        PickingOrderDto row = mock(PickingOrderDto.class);
        when(transactionService.unpick(order, reservation)).thenReturn(row);

        assertThat(service.unpickReservationById(25L)).isSameAs(row);
    }

    /** An ACTIVE reservation was never picked, and a taken-back one is gone: both are a 404, and nothing runs. */
    @Test
    void isA404ForAReservationThatIsNotConsumed() {
        when(reservationRepository.findByIdAndStatus(25L, ReservationStatus.CONSUMED)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unpickReservationById(25L)).isInstanceOf(ResourceNotFoundException.class);

        verify(transactionService, never()).unpick(any(), any());
    }
}
