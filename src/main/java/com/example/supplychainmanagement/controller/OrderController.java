package com.example.supplychainmanagement.controller;

import com.example.supplychainmanagement.entity.Order;
import com.example.supplychainmanagement.exception.ResourceNotFoundException;
import com.example.supplychainmanagement.service.OrderService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/orders")
@AllArgsConstructor
public class OrderController {
    private final OrderService orderService;

    @GetMapping
    public Flux<Order> getOrders() {
        return orderService.findAll();
    }

    /*
    @GetMapping("/{id}")
    public Mono<Order> getOrder(@PathVariable Long id) {
        return orderService.findById(id)
                .onErrorResume(ResourceNotFoundException.class, ignored -> Mono.error(ignored));
    }
     */

    @GetMapping("/{orderNo}")
    public Mono<Order> getOrderByOrderNo(@PathVariable long orderNo) {
        return orderService.findByOrderNo(orderNo);
    }

    @PostMapping("/")
    public Mono<Order> createOrder(@RequestBody Order order) {
        return orderService.create(order);
    }

    @PutMapping("/{id}")
    public Mono<Order> updateOrder(@PathVariable Long id, @RequestBody Order order) {
        return orderService.update(id, order);
    }

    @DeleteMapping("/{id}")
    public Mono<Void> deleteOrder(@PathVariable Long id) {
        return orderService.deleteById(id);
    }
}
