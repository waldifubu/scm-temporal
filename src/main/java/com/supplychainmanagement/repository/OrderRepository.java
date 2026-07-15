package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "orderItems.product.categories", "orderItems.product.components", "customer"})
    Optional<Order> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "orderItems.product.categories", "orderItems.product.components", "customer"})
    Page<Order>  findAllByCustomer(User customer,  Pageable pageable);

    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "orderItems.product.categories", "orderItems.product.components", "customer"})
    Page<Order> findAllBy(Pageable pageable);

    // @TODO: Dangerous too use, because no limit
    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "orderItems.product.categories", "orderItems.product.components", "customer"})
    List<Order> findAllBy();

    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "orderItems.product.categories", "orderItems.product.components", "customer"})
    Optional<Order> findByOrderNo(Long orderNo);

    boolean existsByOrderNo(Long orderNo);

    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "orderItems.product.categories", "orderItems.product.components", "customer"})
    List<Order> findAllByStatus(OrderStatus orderStatus);
}
