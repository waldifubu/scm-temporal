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

@RestController
@RequestMapping({"/api/{version}/orders"})
@AllArgsConstructor
public class OrderController {
    private final OrderService orderService;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;

    // CUSTOMER may see their own order list; ADMIN/MANAGER are included because
    // OrderService.findAllByUser deliberately branches to all orders for admins.
    @GetMapping(path = "", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','CUSTOMER')")
    public PageResponse<OrderSummaryDto> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "ASC") String order,
            @AuthenticationPrincipal User authUser) {
        Sort.Direction dir = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sort));

        return toSummaryPage(orderService.findAllByUser(authUser, pageable));
    }

    @GetMapping(path = "/new", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public PageResponse<OrderSummaryDto> getAllOrdersByStatus(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "dueDate") String sort,
            @RequestParam(defaultValue = "ASC") String order,
            @RequestParam(defaultValue = "CREATED") OrderStatus status
    ) {
        Sort.Direction dir = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sort));

        return toSummaryPage(orderService.findAllByStatus(status, pageable));
    }

    /**
     * Accepts the order towards the customer - the counterpart to {@link #rejectOrder}. These are
     * the two possible answers to an incoming order, so they are shaped alike.
     * <p>
     * Deliberately a POST of its own and not a side effect of reading the order: acknowledging is a
     * commercial commitment, and it must not happen because someone opened the detail view.
     */
    @PostMapping(path = "/{orderNo}/acknowledge", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public OrderSummaryDto acknowledgeOrder(@PathVariable Long orderNo,
                                            @AuthenticationPrincipal User authUser) {
        Order order = orderService.findByOrderNo(orderNo);

        // Status guard and delivery date both live in the service: confirming a date needs the
        // availability check, which is not a controller's business.
        return toSummaryDto(orderService.acknowledge(order, userService.getAuthenticatedUserId(authUser)));
    }

    @PostMapping(path = "/{orderNo}/reject", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public OrderSummaryDto rejectOrder(@PathVariable Long orderNo,
                                       @AuthenticationPrincipal User authUser) {
        Order order = orderService.findByOrderNo(orderNo);
        if (order.getStatus() == OrderStatus.REJECTED) {
            throw new IllegalArgumentException("Order is already rejected");
        }

        order.setStatus(OrderStatus.REJECTED);
        return toSummaryDto(orderService.update(order.getId(), order, userService.getAuthenticatedUserId(authUser)));
    }

    @GetMapping(path = "/{orderNo}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE','CUSTOMER')")
    public OrderDetailsDto getOrderByOrderNo(@PathVariable Long orderNo,
                                             @AuthenticationPrincipal User authUser) {
        // A customer may only see an order they are the customer of - checked in the service,
        // where the role branch for the order list already lives.
        return toDetailsDto(orderService.findByOrderNoForUser(orderNo, authUser));
    }



    @PostMapping(path = "", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','CUSTOMER')")
    public ResponseEntity<OrderDetailsDto> createOrder(@RequestBody(required = true) Order order,
                                                       @AuthenticationPrincipal User authUser) {
        OrderDetailsDto createdOrder = toDetailsDto(orderService.create(order, authUser));
        eventPublisher.publishEvent(new OrderCreatedEvent(String.valueOf(order.getOrderNo()), authUser.getUsername(), order.getOrderDate()));
        return ResponseEntity.status(HttpStatus.CREATED).body(createdOrder);
    }

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
                        item.getProduct() != null ? item.getProduct().getName() : null,
                        item.getFulfillmentStatus()
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
