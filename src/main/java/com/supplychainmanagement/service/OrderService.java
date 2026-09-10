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

    /**
     * The same lookup, but scoped to what the caller is allowed to see: a customer only gets an
     * order they are the customer of. Privileged roles are unrestricted.
     */
    Order findByOrderNoForUser(Long orderNo, org.springframework.security.core.userdetails.User authUser);

    Order create(Order order, org.springframework.security.core.userdetails.User user);

    /**
     * Accepts an incoming order and confirms a delivery date for it - the commercial answer a
     * customer waits for, the equivalent of an EDIFACT ORDRSP / X12 855.
     *
     * @param userId the acting user for the audit trail, may be null
     */
    Order acknowledge(Order order, Long userId);

    Order update(Long id, Order order);

    Order update(Long id, Order order, Long userId);

    void deleteById(Long id);
}
