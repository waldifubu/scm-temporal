package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.RoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The quantity limit per order line. It is checked after repeated articles are merged, so it has
 * to be a 400 from the service - as @Max on the entity it only fired at flush time, as a 500.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceLineQuantityTest {

    private static final Long ARTICLE_NO = 1001L;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private RoleService roleService;

    @InjectMocks
    private OrderServiceImpl service;

    private final org.springframework.security.core.userdetails.User manager =
            new org.springframework.security.core.userdetails.User("manager", "", List.of());

    @BeforeEach
    void privilegedCallerAndKnownCustomer() {
        User customer = new User();
        customer.setId(3L);
        when(roleService.isPrivilegedUser(any())).thenReturn(true);
        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(new User()));
        when(userRepository.findById(3L)).thenReturn(Optional.of(customer));
    }

    private static OrderItem line(int quantity) {
        Product product = new Product();
        product.setArticleNo(ARTICLE_NO);

        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(quantity);
        return item;
    }

    private static Order orderWith(OrderItem... items) {
        User customer = new User();
        customer.setId(3L);

        Order order = new Order();
        order.setCustomer(customer);
        order.setOrderItems(new LinkedHashSet<>(List.of(items)));
        return order;
    }

    /** 11 and 11 are fine on their own; merged into one line they are 22 and over the limit. */
    @Test
    void rejectsALineThatExceedsTheLimitOnlyAfterTheMerge() {
        Order order = orderWith(line(11), line(11));

        assertThatThrownBy(() -> service.create(order, manager))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Article " + ARTICLE_NO)
                .hasMessageContaining("quantity 22")
                .hasMessageContaining("limit of " + OrderServiceImpl.MAX_LINE_QUANTITY);

        verify(orderRepository, never()).save(any());
    }

    @Test
    void rejectsASingleLineOverTheLimit() {
        assertThatThrownBy(() -> service.create(orderWith(line(21)), manager))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** Exactly the limit is still allowed: 12 + 8 become one line of 20, and the order is saved. */
    @Test
    void acceptsALineOfExactlyTheLimit() {
        Product product = new Product();
        product.setArticleNo(ARTICLE_NO);
        when(productRepository.findWithComponentsByArticleNo(ARTICLE_NO)).thenReturn(Optional.of(product));
        when(orderRepository.save(any(Order.class))).thenAnswer(call -> {
            Order saved = call.getArgument(0);
            saved.setId(42L);
            return saved;
        });

        Order order = orderWith(line(12), line(8));
        when(orderRepository.findWithDetailsById(42L)).thenReturn(Optional.of(order));

        Order created = service.create(order, manager);

        assertThat(created.getOrderItems()).extracting(OrderItem::getQuantity).containsExactly(20);
        assertThat(created.getAmountOfItems()).isEqualTo(20);
    }
}
