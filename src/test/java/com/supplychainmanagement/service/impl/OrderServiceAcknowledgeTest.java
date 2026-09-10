package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.fullfillment.AvailableOrderItemDto;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.ProductionService;
import com.supplychainmanagement.service.RoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceAcknowledgeTest {

    private static final int IN_STOCK_LEAD_DAYS = 2;
    private static final int REPLENISHMENT_LEAD_DAYS = 10;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private RoleService roleService;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private ProductionService productionService;

    @InjectMocks
    private OrderServiceImpl service;

    @BeforeEach
    void setLeadTimes() {
        // @Value fields are not populated outside a Spring context - without this both lead times
        // would be 0 and every promised date would land on the next working day.
        ReflectionTestUtils.setField(service, "inStockLeadDays", IN_STOCK_LEAD_DAYS);
        ReflectionTestUtils.setField(service, "replenishmentLeadDays", REPLENISHMENT_LEAD_DAYS);
    }

    private Order orderInStatus(OrderStatus status) {
        User customer = new User();
        customer.setId(3L);

        Product product = new Product();
        product.setArticleNo(1001L);
        product.setUnitPrice(new BigDecimal("19.99"));

        OrderItem item = new OrderItem();
        item.setId(11L);
        item.setProduct(product);
        item.setQuantity(2);
        item.setFulfillmentStatus(FulfillmentStatus.WAITING);

        Order order = new Order();
        order.setId(42L);
        order.setOrderNo(1042L);
        order.setStatus(status);
        order.setCustomer(customer);
        order.setOrderItems(new LinkedHashSet<>(List.of(item)));

        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
        when(orderRepository.findWithDetailsById(42L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));
        when(userRepository.findById(3L)).thenReturn(Optional.of(customer));
        when(productRepository.findWithComponentsByArticleNo(1001L)).thenReturn(Optional.of(product));

        return order;
    }

    private void availability(boolean covered) {
        when(productionService.checkItems(any(Order.class))).thenReturn(List.of(
                new AvailableOrderItemDto(11L, 1001L, 2, covered ? 5 : 0, covered, covered ? 7L : null,
                        FulfillmentStatus.WAITING)));
    }

    /**
     * Counted rather than added, so this does not simply repeat the implementation: it walks every
     * day between the two dates and counts the ones that are not a weekend.
     */
    private long workingDaysBetween(LocalDate from, LocalDate to) {
        return from.datesUntil(to.plusDays(1))
                .skip(1)
                .filter(date -> date.getDayOfWeek() != DayOfWeek.SATURDAY
                        && date.getDayOfWeek() != DayOfWeek.SUNDAY)
                .count();
    }

    @Test
    void promisesTheShortLeadTimeWhenEveryLineIsCovered() {
        Order order = orderInStatus(OrderStatus.CREATED);
        availability(true);

        var acknowledged = service.acknowledge(order, 99L);

        assertThat(acknowledged.getStatus()).isEqualTo(OrderStatus.ACKNOWLEDGED);
        assertThat(workingDaysBetween(LocalDate.now(), acknowledged.getDeliveryDate().toLocalDate()))
                .isEqualTo(IN_STOCK_LEAD_DAYS);
    }

    /** A line that has to be replenished first cannot be promised for the day after tomorrow. */
    @Test
    void promisesTheLongLeadTimeWhenALineIsNotCovered() {
        Order order = orderInStatus(OrderStatus.CREATED);
        availability(false);

        var acknowledged = service.acknowledge(order, 99L);

        assertThat(workingDaysBetween(LocalDate.now(), acknowledged.getDeliveryDate().toLocalDate()))
                .isEqualTo(REPLENISHMENT_LEAD_DAYS);
    }

    /** The promised date is a shipping day, never a weekend. */
    @Test
    void neverPromisesAWeekend() {
        Order order = orderInStatus(OrderStatus.CREATED);
        availability(true);

        var acknowledged = service.acknowledge(order, 99L);

        assertThat(acknowledged.getDeliveryDate().getDayOfWeek())
                .isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        assertThat(acknowledged.getDeliveryDate().getHour()).isEqualTo(17);
    }

    /** Asked for later than we need: shipping earlier than requested is not a favour. */
    @Test
    void keepsADueDateTheCustomerAskedForFurtherOut() {
        Order order = orderInStatus(OrderStatus.CREATED);
        LocalDate requested = LocalDate.now().plusMonths(2);
        order.setDueDate(requested);
        availability(true);

        var acknowledged = service.acknowledge(order, 99L);

        assertThat(acknowledged.getDeliveryDate().toLocalDate()).isEqualTo(requested);
    }

    /** Asked for earlier than we can manage: the promise has to be one that can be kept. */
    @Test
    void ignoresADueDateEarlierThanTheLeadTimeAllows() {
        Order order = orderInStatus(OrderStatus.CREATED);
        order.setDueDate(LocalDate.now());
        availability(false);

        var acknowledged = service.acknowledge(order, 99L);

        assertThat(workingDaysBetween(LocalDate.now(), acknowledged.getDeliveryDate().toLocalDate()))
                .isEqualTo(REPLENISHMENT_LEAD_DAYS);
    }

    /**
     * Confirming is only meaningful from the incoming state - an order already being fulfilled must
     * not be thrown back to ACKNOWLEDGED, and the guard has to bite before anything is written.
     */
    @Test
    void refusesAnOrderThatIsNoLongerInCreated() {
        Order order = orderInStatus(OrderStatus.IN_FULFILLMENT);

        assertThatThrownBy(() -> service.acknowledge(order, 99L))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("IN_FULFILLMENT");

        assertThat(order.getDeliveryDate()).isNull();
        verifyNoInteractions(productionService, applicationEventPublisher);
    }

    /**
     * One article, one line - but a caller repeating an article is not an error, it is an order for
     * more of it. The two lines are folded into one and the quantities added, which is also what
     * keeps the unique constraint on order_items from ever firing.
     */
    @Test
    void addsUpTwoLinesCarryingTheSameArticle() {
        Order order = orderInStatus(OrderStatus.CREATED);
        OrderItem first = order.getOrderItems().iterator().next();   // article 1001, quantity 2

        Product sameArticle = new Product();
        sameArticle.setArticleNo(1001L);
        OrderItem repeated = new OrderItem();
        repeated.setId(12L);
        repeated.setProduct(sameArticle);
        repeated.setQuantity(3);

        order.setOrderItems(new LinkedHashSet<>(List.of(first, repeated)));
        availability(true);

        var acknowledged = service.acknowledge(order, 99L);

        assertThat(acknowledged.getOrderItems()).hasSize(1);
        assertThat(acknowledged.getOrderItems().iterator().next().getQuantity()).isEqualTo(5);
    }

    /** The first occurrence survives, so an existing line grows rather than being replaced. */
    @Test
    void keepsTheFirstLineWhenFoldingARepeatedArticle() {
        Order order = orderInStatus(OrderStatus.CREATED);
        OrderItem first = order.getOrderItems().iterator().next();

        Product sameArticle = new Product();
        sameArticle.setArticleNo(1001L);
        OrderItem repeated = new OrderItem();
        repeated.setId(12L);
        repeated.setProduct(sameArticle);
        repeated.setQuantity(1);

        order.setOrderItems(new LinkedHashSet<>(List.of(first, repeated)));
        availability(true);

        service.acknowledge(order, 99L);

        assertThat(order.getOrderItems().iterator().next().getId()).isEqualTo(11L);
    }
}
