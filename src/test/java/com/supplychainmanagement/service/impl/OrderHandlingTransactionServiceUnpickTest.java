package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.reservation.ReserveItem;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Taking a pick back: the line to WAITING, the stock back onto the shelf, the reservation deleted. The stock side
 * (and the deletion) is {@code InventoryService.restoreWithRetry}; what this boundary decides is which lines may
 * go back at all, and that a failure over there leaves the line where it was.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderHandlingTransactionServiceUnpickTest {

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
    void pickedLine() {
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
        line.setFulfillmentStatus(FulfillmentStatus.PICKED);

        Storehouse storehouse = new Storehouse();
        storehouse.setId(7L);

        reservation = Reservation.active(line, SKU, 3, storehouse);
        reservation.setId(25L);
        reservation.consume();

        when(orderItemRepository.save(any(OrderItem.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void takesAPickedLineBackToWaitingAndHandsTheStockBack() {
        when(inventoryService.restoreWithRetry(any(), anyList())).thenReturn(List.of(reservation));

        var result = service.unpick(order, reservation);

        assertThat(line.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.WAITING);
        assertThat(result.fulfillmentStatus()).isEqualTo(FulfillmentStatus.WAITING);
        assertThat(result.reservationId()).isEqualTo(25L);
    }

    @SuppressWarnings("unchecked")
    @Test
    void asksTheInventoryForTheLinesOwnQuantityInItsStorehouse() {
        when(inventoryService.restoreWithRetry(any(), anyList())).thenReturn(List.of(reservation));

        service.unpick(order, reservation);

        ArgumentCaptor<List<ReserveItem>> items = ArgumentCaptor.forClass(List.class);
        verify(inventoryService).restoreWithRetry(org.mockito.ArgumentMatchers.eq(42L), items.capture());
        assertThat(items.getValue()).containsExactly(new ReserveItem(11L, SKU, 3, 7L));
    }

    /** The reservation is the inventory's to delete, in its own transaction - never written from this one. */
    @Test
    void leavesTheReservationToTheInventory() {
        when(inventoryService.restoreWithRetry(any(), anyList())).thenReturn(List.of(reservation));

        service.unpick(order, reservation);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONSUMED);
    }

    /** Only PICKED goes back: from the packing on, the goods are not where a pick put them. */
    @ParameterizedTest
    @EnumSource(value = FulfillmentStatus.class, names = {"PICKED"}, mode = EnumSource.Mode.EXCLUDE)
    void refusesALineThatIsNotPickedWith409AndTouchesNothing(FulfillmentStatus status) {
        line.setFulfillmentStatus(status);

        assertThatThrownBy(() -> service.unpick(order, reservation))
                .isInstanceOfSatisfying(APIException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("only a PICKED line can be taken back");
                });

        verify(inventoryService, never()).restoreWithRetry(any(), anyList());
        assertThat(line.getFulfillmentStatus()).isEqualTo(status);
    }

    /** Nothing went back onto the shelf: the exception rolls the line back to PICKED, and says why. */
    @Test
    void abortsWhenNothingWasHandedBack() {
        when(inventoryService.restoreWithRetry(any(), anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> service.unpick(order, reservation))
                .isInstanceOfSatisfying(APIException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("Nothing taken back for reservation 25");
                });
    }

    @Test
    void wrapsAFailureOfTheInventoryInTheSameWay() {
        when(inventoryService.restoreWithRetry(any(), anyList())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.unpick(order, reservation))
                .isInstanceOfSatisfying(APIException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("Failed to take back reservation 25").contains("db down");
                });
    }

    @Test
    void refusesAReservationOfAnotherOrder() {
        Order other = new Order();
        other.setId(99L);

        assertThatThrownBy(() -> service.unpick(other, reservation))
                .isInstanceOfSatisfying(APIException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(inventoryService, never()).restoreWithRetry(any(), anyList());
    }
}
