package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.reservation.ReservationResult;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.event.OrderStatusChangedEvent;
import com.supplychainmanagement.exception.UnsufficientException;
import com.supplychainmanagement.model.enums.FullfillmentStatus;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.repository.StockRepository;
import com.supplychainmanagement.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FullfillmentServiceReserveItemsTest {

    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");
    private static final String USERNAME = "manager";
    private static final Long USER_ID = 99L;

    @Mock
    private StockRepository stockRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private FullfillmentServiceImpl service;

    private Product product() {
        Product product = new Product();
        product.setArticleNo(1001L);
        product.setSku(SKU);
        return product;
    }

    private Order orderRequesting(int quantity) {
        OrderItem item = new OrderItem();
        item.setProduct(product());
        item.setQuantity(quantity);

        Order order = new Order();
        order.setId(42L);
        order.setOrderNo(1042L);
        order.setStatus(OrderStatus.CREATED);
        order.setOrderItems(List.of(item));
        return order;
    }

    private User actingUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        return user;
    }

    private Stock stock(int onHand, int reserved) {
        Storehouse storehouse = new Storehouse();
        storehouse.setId(7L);

        Stock stock = new Stock();
        stock.setSku(SKU);
        stock.setOnHand(onHand);
        stock.setReserved(reserved);
        stock.setStorehouse(storehouse);
        return stock;
    }

    /**
     * No storehouse covers the line, so checkItems reports storehouseId == null. That null must
     * never reach the reservation - it would fail there with a misleading "Stock not found".
     */
    @Test
    void rejectsUncoveredLinesBeforeReserving() {
        when(stockRepository.findEligibleBySku(any(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service.reserveItems(orderRequesting(5), USERNAME))
                .isInstanceOf(UnsufficientException.class)
                .hasMessageContaining("article 1001")
                .hasMessageContaining("requested 5")
                .hasMessageNotContaining("Stock not found");

        verifyNoInteractions(inventoryService, orderItemRepository, orderRepository, eventPublisher);
    }

    @Test
    void namesEveryUncoveredLine() {
        when(stockRepository.findEligibleBySku(any(), anyInt())).thenReturn(List.of());

        Product second = new Product();
        second.setArticleNo(1002L);
        second.setSku(UUID.randomUUID());
        OrderItem secondItem = new OrderItem();
        secondItem.setProduct(second);
        secondItem.setQuantity(2);

        Order order = orderRequesting(5);
        order.setOrderItems(List.of(order.getOrderItems().getFirst(), secondItem));

        assertThatThrownBy(() -> service.reserveItems(order, USERNAME))
                .isInstanceOf(UnsufficientException.class)
                .hasMessageContaining("article 1001")
                .hasMessageContaining("article 1002");
    }

    /**
     * A repeated call on an already reserved order must stay idempotent. checkItems reports the
     * line as unavailable in that situation - the stock is held by this very order - so the
     * availability guard has to step aside and let the reservation return what already exists.
     */
    @Test
    void staysIdempotentWhenTheOrderAlreadyHoldsReservations() {
        Storehouse storehouse = new Storehouse();
        storehouse.setId(7L);
        Reservation existing = Reservation.active("42", SKU, 5, storehouse);

        when(stockRepository.findEligibleBySku(any(), anyInt())).thenReturn(List.of());
        when(reservationRepository.findActive("42")).thenReturn(List.of(existing));
        when(productRepository.findByArticleNo(1001L)).thenReturn(Optional.of(product()));
        when(inventoryService.reserveWithRetry(anyString(), anyList()))
                .thenReturn(new ReservationResult(List.of(existing), false));

        var result = service.reserveItems(orderRequesting(5), USERNAME);

        assertThat(result.created()).isFalse();
        assertThat(result.reservations()).containsExactly(existing);
        verify(inventoryService).reserveWithRetry(anyString(), anyList());
    }

    /**
     * Three of four available: the covered lines are reserved and marked RESERVED, the uncovered
     * one is not handed to the reservation at all and keeps its status.
     */
    @Test
    void reservesTheCoveredLinesAndLeavesTheRestWaiting() {
        UUID coveredSku = SKU;
        UUID uncoveredSku = UUID.randomUUID();

        Product uncovered = new Product();
        uncovered.setArticleNo(1002L);
        uncovered.setSku(uncoveredSku);
        OrderItem uncoveredItem = new OrderItem();
        uncoveredItem.setProduct(uncovered);
        uncoveredItem.setQuantity(2);
        uncoveredItem.setFullfillmentStatus(FullfillmentStatus.WAITING);

        Order order = orderRequesting(3);
        OrderItem coveredItem = order.getOrderItems().getFirst();
        order.setOrderItems(List.of(coveredItem, uncoveredItem));

        when(stockRepository.findEligibleBySku(coveredSku, 3)).thenReturn(List.of(stock(10, 2)));
        when(stockRepository.findEligibleBySku(uncoveredSku, 2)).thenReturn(List.of());
        when(productRepository.findByArticleNo(1001L)).thenReturn(Optional.of(product()));

        Storehouse storehouse = new Storehouse();
        storehouse.setId(7L);
        Reservation created = Reservation.active("42", coveredSku, 3, storehouse);
        when(inventoryService.reserveWithRetry(anyString(), anyList()))
                .thenReturn(new ReservationResult(List.of(created), true));

        service.reserveItems(order, USERNAME);

        // only the covered line is handed over
        ArgumentCaptor<List<ReserveItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(inventoryService).reserveWithRetry(anyString(), captor.capture());
        assertThat(captor.getValue()).extracting(ReserveItem::sku).containsExactly(coveredSku);

        assertThat(coveredItem.getFullfillmentStatus()).isEqualTo(FullfillmentStatus.RESERVED);
        assertThat(uncoveredItem.getFullfillmentStatus()).isEqualTo(FullfillmentStatus.WAITING);
    }

    /** A repeat call must attempt only the line that is still missing. */
    @Test
    void repeatedCallRetriesOnlyTheOutstandingLine() {
        UUID outstandingSku = UUID.randomUUID();

        Product outstanding = new Product();
        outstanding.setArticleNo(1002L);
        outstanding.setSku(outstandingSku);
        OrderItem outstandingItem = new OrderItem();
        outstandingItem.setProduct(outstanding);
        outstandingItem.setQuantity(2);

        Order order = orderRequesting(3);
        OrderItem reservedItem = order.getOrderItems().getFirst();
        reservedItem.setFullfillmentStatus(FullfillmentStatus.RESERVED);
        order.setOrderItems(List.of(reservedItem, outstandingItem));
        order.setStatus(OrderStatus.IN_FULFILLMENT);

        Storehouse storehouse = new Storehouse();
        storehouse.setId(7L);
        Reservation existing = Reservation.active("42", SKU, 3, storehouse);

        // the already reserved line reads as unavailable - its stock is booked to this order
        when(stockRepository.findEligibleBySku(SKU, 3)).thenReturn(List.of());
        when(stockRepository.findEligibleBySku(outstandingSku, 2)).thenReturn(List.of(stock(5, 0)));
        when(reservationRepository.findActive("42")).thenReturn(List.of(existing));
        when(productRepository.findByArticleNo(1002L)).thenReturn(Optional.of(outstanding));

        Reservation added = Reservation.active("42", outstandingSku, 2, storehouse);
        when(inventoryService.reserveWithRetry(anyString(), anyList()))
                .thenReturn(new ReservationResult(List.of(existing, added), true));

        service.reserveItems(order, USERNAME);

        ArgumentCaptor<List<ReserveItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(inventoryService).reserveWithRetry(anyString(), captor.capture());
        assertThat(captor.getValue()).extracting(ReserveItem::sku).containsExactly(outstandingSku);

        assertThat(reservedItem.getFullfillmentStatus()).isEqualTo(FullfillmentStatus.RESERVED);
        assertThat(outstandingItem.getFullfillmentStatus()).isEqualTo(FullfillmentStatus.RESERVED);
    }

    @Test
    void reservesWhenEveryLineIsCovered() {
        when(stockRepository.findEligibleBySku(any(), anyInt())).thenReturn(List.of(stock(10, 2)));
        when(productRepository.findByArticleNo(1001L)).thenReturn(Optional.of(product()));
        when(inventoryService.reserveWithRetry(anyString(), anyList()))
                .thenReturn(new ReservationResult(List.of(), true));

        var result = service.reserveItems(orderRequesting(3), USERNAME);

        assertThat(result.created()).isTrue();
        verify(inventoryService).reserveWithRetry(anyString(), anyList());
        verify(orderItemRepository).saveAll(anyList());
    }

    /** The status change is attributed to the caller, resolved from the login identifier. */
    @Test
    void publishesTheStatusChangeWithTheActingUser() {
        Storehouse storehouse = new Storehouse();
        storehouse.setId(7L);
        Reservation created = Reservation.active("42", SKU, 3, storehouse);

        when(stockRepository.findEligibleBySku(any(), anyInt())).thenReturn(List.of(stock(10, 2)));
        when(productRepository.findByArticleNo(1001L)).thenReturn(Optional.of(product()));
        when(userRepository.findByUsernameOrEmail(USERNAME, USERNAME)).thenReturn(Optional.of(actingUser()));
        when(inventoryService.reserveWithRetry(anyString(), anyList()))
                .thenReturn(new ReservationResult(List.of(created), true));

        service.reserveItems(orderRequesting(3), USERNAME);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());

        assertThat(captor.getValue())
                .isInstanceOf(OrderStatusChangedEvent.class)
                .satisfies(event -> {
                    OrderStatusChangedEvent statusChange = (OrderStatusChangedEvent) event;
                    assertThat(statusChange.orderId()).isEqualTo(42L);
                    assertThat(statusChange.userId()).isEqualTo(USER_ID);
                    assertThat(statusChange.previousStatus()).isEqualTo(OrderStatus.CREATED);
                    assertThat(statusChange.newStatus()).isEqualTo(OrderStatus.IN_FULFILLMENT);
                });
    }

    /**
     * CustomUserDetailsService puts whatever was typed at login into the principal, so the
     * identifier reaching reserveItems can be an email address. Attribution has to work either way.
     */
    @Test
    void resolvesTheActingUserWhenTheyLoggedInWithTheirEmail() {
        String email = "manager@example.com";

        Storehouse storehouse = new Storehouse();
        storehouse.setId(7L);
        Reservation created = Reservation.active("42", SKU, 3, storehouse);

        when(stockRepository.findEligibleBySku(any(), anyInt())).thenReturn(List.of(stock(10, 2)));
        when(productRepository.findByArticleNo(1001L)).thenReturn(Optional.of(product()));
        when(userRepository.findByUsernameOrEmail(email, email)).thenReturn(Optional.of(actingUser()));
        when(inventoryService.reserveWithRetry(anyString(), anyList()))
                .thenReturn(new ReservationResult(List.of(created), true));

        service.reserveItems(orderRequesting(3), email);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(((OrderStatusChangedEvent) captor.getValue()).userId()).isEqualTo(USER_ID);
    }

    /** Nothing was reserved, so the order status must not move and no history event may be raised. */
    @Test
    void doesNotPublishAStatusChangeWhenNothingCouldBeReserved() {
        Storehouse storehouse = new Storehouse();
        storehouse.setId(7L);
        Reservation existing = Reservation.active("42", SKU, 5, storehouse);

        when(stockRepository.findEligibleBySku(any(), anyInt())).thenReturn(List.of());
        when(reservationRepository.findActive("42")).thenReturn(List.of(existing));
        when(inventoryService.reserveWithRetry(anyString(), anyList()))
                .thenReturn(new ReservationResult(List.of(), false));

        Order order = orderRequesting(5);
        service.reserveItems(order, USERNAME);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        verifyNoInteractions(eventPublisher, orderRepository);
    }
}
