package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    @EntityGraph(attributePaths = {"order", "product"})
    Optional<OrderItem> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"order", "product"})
    List<OrderItem> findAllBy();

    Optional<OrderItem> findByOrderAndProductSku(Order order, UUID productSku);
}
