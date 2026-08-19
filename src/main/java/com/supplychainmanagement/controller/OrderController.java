package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.order.OrderDetailsDto;
import com.supplychainmanagement.dto.order.OrderItemDto;
import com.supplychainmanagement.dto.order.OrderSummaryDto;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.event.OrderCreatedEvent;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.service.OrderService;
import com.supplychainmanagement.service.UserService;
import lombok.AllArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping({"/api/{version}/orders"})
@AllArgsConstructor
public class OrderController {
    private final OrderService orderService;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;

    @GetMapping(path = "", version = "1.0")
    public Mono<PageResponse<OrderSummaryDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "ASC") String order,
            @AuthenticationPrincipal User authUser) {
        Sort.Direction dir = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sort));
        var orders = orderService.findAllByUser(authUser, pageable);

        return orders.map(this::toSummaryPage);
    }

    @GetMapping(path = "/new", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public Mono<PageResponse<OrderSummaryDto>> getAllOrdersByStatus(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "dueDate") String sort,
            @RequestParam(defaultValue = "ASC") String order,
            @RequestParam(defaultValue = "CREATED") OrderStatus status
    ) {
        Sort.Direction dir = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sort));
        var orders = orderService.findAllByStatus(status, pageable);

        return orders.map(this::toSummaryPage);
    }

    @PostMapping(path = "/{orderNo}/reject", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public Mono<OrderSummaryDto> rejectOrder(@PathVariable Long orderNo,
                                             @AuthenticationPrincipal User authUser) {
        return orderService.findByOrderNo(orderNo)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Order not found: " + orderNo)))
                .flatMap(o -> {
                    if (o.getStatus() == OrderStatus.REJECTED) {
                        return Mono.error(new IllegalArgumentException("Order is already rejected"));
                    }

                    o.setStatus(OrderStatus.REJECTED);
                    Long actingUserId = getAuthenticatedUserId(authUser);
                    return orderService.update(o.getId(), o, actingUserId);
                })
                .map(this::toSummaryDto);
    }

    public Long getAuthenticatedUserId(User authUser) {
        return userService.findByUsernameOrEmail(authUser.getUsername())
                .map(com.supplychainmanagement.entity.users.User::getId)
                .block();
    }

    /*
    OrderApprovedEvent
    OrderPickedEvent
    OrderShippedEvent
    OrderDeliveredEvent
     */


/*
    @GetMapping("/{id}")
    public Mono<Order> getOrder(@PathVariable Long id) {
        return orderService.findById(id)
                .onErrorResume(ResourceNotFoundException.class, ignored -> Mono.error(ignored));
    }
*/

    @GetMapping(path = "/{orderNo}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE')")
    public Mono<OrderDetailsDto> getOrderByOrderNo(@PathVariable Long orderNo,
                                                   @AuthenticationPrincipal User authUser) {
        return orderService.findByOrderNo(orderNo)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Order not found: " + orderNo)))
                .flatMap(o -> {
                    if (o.getStatus() == OrderStatus.ACKNOWLEDGED) {
                        return Mono.error(new IllegalArgumentException("Order is already acknowledged"));
                    }

                    o.setStatus(OrderStatus.ACKNOWLEDGED);
                    Long actingUserId = getAuthenticatedUserId(authUser);
                    return orderService.update(o.getId(), o, actingUserId);
                })
                .map(this::toDetailsDto);
    }

    @PostMapping(path = "", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','CUSTOMER')")
    public ResponseEntity<OrderDetailsDto> createOrder(@RequestBody(required = true) Order order,
                                                       @AuthenticationPrincipal User authUser) {
        OrderDetailsDto createdOrder = toDetailsDto(orderService.create(order, authUser));
        eventPublisher.publishEvent(new OrderCreatedEvent(String.valueOf(order.getOrderNo()), authUser.getUsername(), order.getOrderDate()));
        return ResponseEntity.status(HttpStatus.CREATED).body(createdOrder);
    }



    /*
        @PutMapping("/{id}")
        public Mono<OrderSummaryDto> updateOrder(@PathVariable Long id, @RequestBody Order order) {
            return orderService.update(id, order).map(this::toSummaryDto);
        }

        @DeleteMapping("/{id}")
        public Mono<Void> deleteOrder(@PathVariable Long id) {
            return orderService.deleteById(id);
        }
    */

    private OrderSummaryDto toSummaryDto(Order order) {
        int qty = 0;
        if (order.getOrderItems() != null) {
            qty = order.getOrderItems().stream().mapToInt(OrderItem::getQuantity).sum();
        }
        order.setAmountOfItems(qty);

        return new OrderSummaryDto(
                order.getOrderNo(),
                order.getAmountOfItems(),
                order.getTotal(),
                order.getDueDate(),
                order.getOrderDate(),
                order.getStatus()
        );
    }

    private OrderDetailsDto toDetailsDto(Order order) {
        if (order.getOrderItems() == null) {
            return new OrderDetailsDto(
                    order.getOrderNo(),
                    order.getTotal(),
                    order.getStatus(),
                    order.getDueDate(),
                    order.getOrderDate(),
                    order.getCustomer() != null ? order.getCustomer().getLastName() : null,
                    java.util.Collections.emptyList()
            );
        }

        var items = order.getOrderItems().stream()
                .map(item -> new OrderItemDto(
                        item.getId(),
                        item.getQuantity(),
                        item.getProduct() != null ? item.getProduct().getName() : null
                ))
                .toList();

        return new OrderDetailsDto(
                order.getOrderNo(),
                order.getTotal(),
                order.getStatus(),
                order.getDueDate(),
                order.getOrderDate(),
                order.getCustomer() != null ? order.getCustomer().getLastName() : null,
                items
        );
    }

    private PageResponse<OrderSummaryDto> toSummaryPage(Page<Order> page) {
        return new PageResponse<>(
                page.map(this::toSummaryDto).getContent(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize()
        );
    }
}
