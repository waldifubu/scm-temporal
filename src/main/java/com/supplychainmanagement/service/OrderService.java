package com.supplychainmanagement.service;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.model.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Deliberately synchronous: blocking JPA sits underneath. These methods used to return Mono/Flux,
 * which rendered {@code @Transactional} useless - the proxy committed as soon as the method
 * returned the (not yet executed) Mono, and the actual database work then ran without a
 * transaction on a boundedElastic thread.
 */
public interface OrderService {

    List<Order> findAll();

    Page<Order> findAll(Pageable pageable);

    Page<Order> findAllByUser(org.springframework.security.core.userdetails.User authUser, Pageable pageable);

    Page<Order> findAllByStatus(OrderStatus orderStatus, Pageable pageable);

    Order findById(Long id);

    Order findByOrderNo(Long orderNo);

    Order create(Order order, org.springframework.security.core.userdetails.User user);

    Order update(Long id, Order order);

    Order update(Long id, Order order, Long userId);

    void deleteById(Long id);
}
