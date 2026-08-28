package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.model.enums.FullfillmentStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FullfillmentServiceReleaseItemsTest {

    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");
    private static final String ORDER_ID = "42";

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private InventoryService inventoryService;

    @InjectMocks
    private FullfillmentServiceImpl service;

    private OrderItem orderItem;

    private Order orderWithActiveReservation() {
        Storehouse storehouse = new Storehouse();
        storehouse.setId(7L);

        Product product = new Product();
        product.setArticleNo(1001L);
        product.setSku(SKU);

        orderItem = new OrderItem();
        orderItem.setProduct(product);
        orderItem.setQuantity(3);
        orderItem.setFullfillmentStatus(FullfillmentStatus.RESERVED);

        Order order = new Order();
        order.setId(42L);
        order.setOrderNo(1042L);
        order.setOrderItems(Set.of(orderItem));

        Reservation reservation = Reservation.active(ORDER_ID, SKU, 3, storehouse);
        when(reservationRepository.findActive(ORDER_ID)).thenReturn(List.of(reservation));

        return order;
    }

    @Test
    void resetsLineItemsToWaitingAfterASuccessfulRelease() {
        Order order = orderWithActiveReservation();

        service.releaseItems(order);

        assertThat(orderItem.getFullfillmentStatus()).isEqualTo(FullfillmentStatus.WAITING);
        verify(orderItemRepository).saveAll(anyList());
    }

    /** The status write must happen after the release, not before it. */
    @Test
    void releasesStockBeforeTouchingTheLineItems() {
        Order order = orderWithActiveReservation();

        service.releaseItems(order);

        InOrder ordered = inOrder(inventoryService, orderItemRepository);
        ordered.verify(inventoryService).releaseWithRetry(anyString(), anyList());
        ordered.verify(orderItemRepository).saveAll(anyList());
    }

    /**
     * The point of the ordering: a failed release must leave the items on RESERVED. Marking them
     * WAITING would claim they are unreserved while the stock is still held.
     */
    @Test
    void keepsTheReservedStatusWhenTheReleaseFails() {
        Order order = orderWithActiveReservation();
        doThrow(new IllegalStateException("release exceeds reserved"))
                .when(inventoryService).releaseWithRetry(anyString(), anyList());

        assertThatThrownBy(() -> service.releaseItems(order))
                .isInstanceOf(IllegalStateException.class);

        assertThat(orderItem.getFullfillmentStatus()).isEqualTo(FullfillmentStatus.RESERVED);
        verify(orderItemRepository, never()).saveAll(any());
    }
}
