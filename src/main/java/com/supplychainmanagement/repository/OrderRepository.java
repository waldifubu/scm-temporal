package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * The orders a shipment carries, each once: a package holds the items of one order, and a
     * shipment may hold packages of several orders of the same customer. Reached over the packages
     * because an order has no reference to a shipment.
     */
    @Query("""
            select distinct o
            from ShipmentPackage sp
              join sp.items pi
              join pi.orderItem oi
              join oi.order o
            where sp.shipment.id = :shipmentId
            """)
    List<Order> findByShipmentId(@Param("shipmentId") Long shipmentId);
    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "orderItems.product.categories", "orderItems.product.components", "customer"})
    Optional<Order> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "orderItems.product.categories", "orderItems.product.components", "customer"})
    Page<Order>  findAllByCustomerAndStatus(User customer, OrderStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "orderItems.product.categories", "orderItems.product.components", "customer"})
    Page<Order> findAllBy(Pageable pageable);

    // @TODO: Dangerous too use, because no limit
    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "orderItems.product.categories", "orderItems.product.components", "customer"})
    List<Order> findAllBy();

    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "orderItems.product.categories", "orderItems.product.components", "customer"})
    Optional<Order> findByOrderNo(Long orderNo);

    boolean existsByOrderNo(Long orderNo);

    @EntityGraph(attributePaths = {"orderItems", "orderItems.product", "orderItems.product.categories", "orderItems.product.components", "customer"})
    Page<Order> findAllByStatus(OrderStatus orderStatus, Pageable pageable);

    /**
     * Several orders in one query, with their lines fetched. For callers that work on the orders
     * after the session has closed - the expiry sweep hands them to releaseItems, which walks
     * {@code orderItems}.
     */
    @EntityGraph(attributePaths = "orderItems")
    List<Order> findWithOrderItemsByIdIn(Collection<Long> ids);
}
