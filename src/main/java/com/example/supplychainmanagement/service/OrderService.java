package com.example.supplychainmanagement.service;

import com.example.supplychainmanagement.entity.Order;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OrderService {
    Flux<Order> findAll();

    Mono<Order> findById(Long id);

    Mono<Order> findByOrderNo(long orderNo);

    Mono<Order> create(Order order);

    Mono<Order> update(Long id, Order order);

    Mono<Void> deleteById(Long id);
}
