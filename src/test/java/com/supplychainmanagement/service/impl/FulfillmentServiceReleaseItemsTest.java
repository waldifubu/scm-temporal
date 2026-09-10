package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.event.OrderStatusChangedEvent;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FulfillmentServiceReleaseItemsTest {

    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");
    private static final String ORDER_ID = "42";
    private static final String USERNAME = "manager";

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private FulfillmentServiceImpl service;

    private OrderItem orderItem;

    private Order orderWithActiveReservation() {
        Storehouse storehouse = new Storehouse();
        storehouse.setId(7L);

        Product product = new Product();
        product.setArticleNo(1001L);
        product.setSku(SKU);

        orderItem = new OrderItem();
        // The id matters here: releaseItems reads it off the reservation to build the ReserveItem.
        orderItem.setId(5L);
        orderItem.setProduct(product);
        orderItem.setQuantity(3);
        orderItem.setFulfillmentStatus(FulfillmentStatus.RESERVED);

        Order order = new Order();
        order.setId(42L);
        order.setOrderNo(1042L);
        order.setOrderItems(Set.of(orderItem));

        Reservation reservation = Reservation.active(orderItem, ORDER_ID, SKU, 3, storehouse);
        // Two answers on purpose: releaseItems looks the reservation up, and after the release -
        // which deletes the row in its own committed transaction - the status check sees it gone.
        when(reservationRepository.findActive(ORDER_ID)).thenReturn(List.of(reservation), List.of());
        when(inventoryService.releaseWithRetry(anyString(), anyList())).thenReturn(List.of(reservation));

        return order;
    }

    @Test
    void resetsLineItemsToWaitingAfterASuccessfulRelease() {
        Order order = orderWithActiveReservation();

        service.releaseItems(order, USERNAME);

        assertThat(orderItem.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.WAITING);
        verify(orderItemRepository).saveAll(anyList());
    }

    /** The status write must happen after the release, not before it. */
    @Test
    void releasesStockBeforeTouchingTheLineItems() {
        Order order = orderWithActiveReservation();

        service.releaseItems(order, USERNAME);

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

        assertThatThrownBy(() -> service.releaseItems(order, USERNAME))
                .isInstanceOf(IllegalStateException.class);

        assertThat(orderItem.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.RESERVED);
        verify(orderItemRepository, never()).saveAll(any());
    }

    /**
     * The mirror image of the IN_FULFILLMENT transition in reserveItems: with the last reservation
     * gone, fulfillment has not started any more and the order drops back to APPROVED - recorded in
     * the audit trail under the caller who released it.
     */
    @Test
    void takesTheOrderBackToApprovedWhenNothingIsHeldAnyMore() {
        Order order = orderWithActiveReservation();
        order.setStatus(OrderStatus.IN_FULFILLMENT);

        User acting = new User();
        acting.setId(99L);
        when(userRepository.findByUsernameOrEmail(USERNAME, USERNAME)).thenReturn(Optional.of(acting));

        service.releaseItems(order, USERNAME);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.APPROVED);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue())
                .isInstanceOf(OrderStatusChangedEvent.class)
                .satisfies(event -> {
                    OrderStatusChangedEvent statusChange = (OrderStatusChangedEvent) event;
                    assertThat(statusChange.userId()).isEqualTo(99L);
                    assertThat(statusChange.previousStatus()).isEqualTo(OrderStatus.IN_FULFILLMENT);
                    assertThat(statusChange.newStatus()).isEqualTo(OrderStatus.APPROVED);
                });
    }

    /**
     * A sweep runs as "system", which resolves to no user at all. The audit row still has to be
     * written - OrderHistory.user_id is nullable for exactly this case.
     */
    @Test
    void stillRecordsTheReleaseWhenTheUserCannotBeResolved() {
        Order order = orderWithActiveReservation();
        order.setStatus(OrderStatus.IN_FULFILLMENT);
        when(userRepository.findByUsernameOrEmail("system", "system")).thenReturn(Optional.empty());

        service.releaseItems(order, "system");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(((OrderStatusChangedEvent) captor.getValue()).userId()).isNull();
    }

    /** Only what has expired is handed back - a line reserved later keeps its hold. */
    @Test
    void releasesOnlyTheExpiredReservations() {
        Order order = orderWithActiveReservation();

        Storehouse storehouse = new Storehouse();
        storehouse.setId(7L);
        Reservation expired = Reservation.active(orderItem, ORDER_ID, SKU, 3, storehouse);
        when(inventoryService.releaseWithRetry(anyString(), anyList())).thenReturn(List.of(expired));

        var released = service.releaseItems(order, List.of(expired), "system");

        // What comes back is what the inventory layer reported released, not the queried list.
        assertThat(released).containsExactly(expired);
        verify(inventoryService).releaseWithRetry(anyString(), anyList());
        assertThat(orderItem.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.WAITING);
    }

    /** Nothing expired is not an error: the sweep visits orders that simply have nothing to do. */
    @Test
    void doesNothingWhenNoReservationHasExpired() {
        Order order = orderWithActiveReservation();
        assertThat(service.releaseItems(order, List.of(), "system")).isEmpty();

        verify(inventoryService, never()).releaseWithRetry(anyString(), anyList());
        assertThat(orderItem.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.RESERVED);
    }
}
