package com.example.supplychainmanagement.repository;

import com.example.supplychainmanagement.entity.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "customer"})
    Optional<Order> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "customer"})
    List<Order> findAllBy();

    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "customer"})
    Optional<Order> findByOrderNo(long orderNo);

    boolean existsByOrderNo(long orderNo);
}
