package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Picking a line hinges on the stock really having been consumed. consume skips a line it cannot
 * consume instead of throwing, so pick has to read what came back rather than rely on an exception.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderHandlingTransactionServicePickTest {

    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");

    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private InventoryService inventoryService;

    @InjectMocks
    private OrderHandlingTransactionService service;

    private Order order;
    private OrderItem line;
    private Reservation reservation;

    @BeforeEach
    void reservedLine() {
        order = new Order();
        order.setId(42L);
        order.setOrderNo(1042L);

        Product product = new Product();
        product.setArticleNo(1001L);
        product.setSku(SKU);

        line = new OrderItem();
        line.setId(11L);
        line.setOrder(order);
        line.setProduct(product);
        line.setQuantity(3);
        line.setFulfillmentStatus(FulfillmentStatus.RESERVED);

        Storehouse storehouse = new Storehouse();
        storehouse.setId(7L);

        reservation = Reservation.active(line, SKU, 3, storehouse);
        reservation.setId(25L);

        when(orderItemRepository.save(any(OrderItem.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void picksTheLineOnceItsReservationWasConsumed() {
        when(inventoryService.consumeWithRetry(any(), anyList())).thenReturn(List.of(reservation));

        var picked = service.pick(order, reservation);

        assertThat(line.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PICKED);
        assertThat(picked.fulfillmentStatus()).isEqualTo(FulfillmentStatus.PICKED);
        assertThat(picked.reservationId()).isEqualTo(25L);
    }

    /**
     * consume sets CONSUMED and commits in its own transaction. pick must not write the reservation a
     * second time: on MariaDB with snapshot isolation that update fails with "Record has changed since
     * last read", because consume changed the row after pick took its snapshot. pick no longer holds a
     * ReservationRepository at all - what is left to guard is that it does not modify the entity
     * either, since a changed managed entity is flushed without any save.
     */
    @Test
    void leavesWritingTheReservationToConsume() {
        when(inventoryService.consumeWithRetry(any(), anyList())).thenReturn(List.of(reservation));

        service.pick(order, reservation);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    }

    /**
     * The case the empty list exists for: nothing was taken out of stock, so neither the reservation
     * nor the line may move on. In production the rollback also takes the line back out of PICKING;
     * a unit test has no transaction, so it asserts on what must not have happened instead.
     */
    @Test
    void abortsWhenNothingWasConsumed() {
        when(inventoryService.consumeWithRetry(any(), anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> service.pick(order, reservation))
                .isInstanceOfSatisfying(APIException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("Nothing consumed for reservation 25");
                });

        assertThat(line.getFulfillmentStatus()).isNotEqualTo(FulfillmentStatus.PICKED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    }
}
