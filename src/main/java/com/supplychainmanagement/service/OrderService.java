package com.supplychainmanagement.service;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.model.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OrderService {
    Flux<Order> findAll();

    Mono<Page<Order>> findAll(Pageable pageable);

    Mono<Page<Order>> findAllByUser(org.springframework.security.core.userdetails.User authUser, Pageable pageable);

    Flux<Order> findAllByStatus(OrderStatus orderStatus);

    Mono<Order> findById(Long id);

    Mono<Order> findByOrderNo(Long orderNo);

    Mono<Order> create(Order order);

    Mono<Order> update(Long id, Order order);

    Mono<Void> deleteById(Long id);
}
