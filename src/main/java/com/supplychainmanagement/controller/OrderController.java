package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.order.OrderSummaryDto;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.service.RoleService;
import com.supplychainmanagement.service.OrderService;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/orders")
@AllArgsConstructor
public class OrderController {
    private final OrderService orderService;
    private final RoleService roleService;

    @GetMapping("")
    public Mono<PageResponse<OrderSummaryDto>> list(
            //@AuthenticationPrincipal AppUserDetails user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "ASC") String order,
            @AuthenticationPrincipal org.springframework.security.core.userdetails.User authUser) {
        Sort.Direction dir = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sort));
        var orders = orderService.findAllByUser(authUser, pageable);

        return orders
                .map(this::toSummaryPage);
    }

    @GetMapping("/new")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public Flux<OrderSummaryDto> getAllOrdersByStatus(
            @RequestParam(defaultValue = "CREATED") OrderStatus status
    ) {
        return orderService.findAllByStatus(status).map(this::toSummaryDto);
    }


/*
    @GetMapping("/{id}")
    public Mono<Order> getOrder(@PathVariable Long id) {
        return orderService.findById(id)
                .onErrorResume(ResourceNotFoundException.class, ignored -> Mono.error(ignored));
    }
*/

    @GetMapping("/{orderNo}")
    public Mono<OrderSummaryDto> getOrderByOrderNo(@PathVariable Long orderNo) {
        return orderService.findByOrderNo(orderNo).map(this::toSummaryDto);
    }

    @PostMapping("")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CUSTOMER')")
    public Mono<OrderSummaryDto> createOrder(@RequestBody Order order) {
        return orderService.create(order).map(this::toSummaryDto);
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

    private PageResponse<OrderSummaryDto> toSummaryPage(Page<Order> page) {
        return new PageResponse<>(
                page.map(this::toSummaryDto).getContent(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize()
        );
    }
}
