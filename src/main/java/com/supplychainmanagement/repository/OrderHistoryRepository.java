package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.OrderHistory;
import com.supplychainmanagement.model.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderHistoryRepository extends JpaRepository<OrderHistory, Long> {
    List<OrderHistory> findByOrderId(Long orderId);

    /**
     * One order's history, newest first. The id breaks a tie and runs the same way: rows written
     * within one transaction carry the same timestamp (measured: 251 of 300 three-row histories), and
     * the later id is the later step - so "newest first" has to hold for them too. With the id
     * ascending, those rows came back in the opposite order to the rest, and with no tie-break at all
     * in whatever order the database happens to return them.
     */
    List<OrderHistory> findByOrderIdOrderByChangedAtDescIdDesc(Long orderId);

    List<OrderHistory> findByUserId(Long userId);

    /**
     * Every status change of every order, a page at a time. {@code orderNo} and {@code status} (the status
     * the order went <em>to</em>) are optional filters; {@code null} means no condition. With {@code latestOnly}
     * only the newest step of each order is listed (the highest id - rows of one transaction share a timestamp), and
     * {@code status} then narrows to the orders that are in that status <em>now</em>. The order and its
     * customer come along through the entity graph - both to-one, so the page stays paged in SQL.
     */
    @EntityGraph(attributePaths = {"order", "order.customer"})
    @Query(value = "select h from OrderHistory h where (:orderNo is null or h.order.orderNo = :orderNo)"
            + " and (:status is null or h.newStatus = :status)"
            + " and (:latestOnly = false or h.id = (select max(h2.id) from OrderHistory h2 where h2.order = h.order))",
            countQuery = "select count(h) from OrderHistory h where (:orderNo is null or h.order.orderNo = :orderNo)"
                    + " and (:status is null or h.newStatus = :status)"
                    + " and (:latestOnly = false or h.id = (select max(h2.id) from OrderHistory h2 where h2.order = h.order))")
    Page<OrderHistory> findEntries(@Param("orderNo") Long orderNo, @Param("status") OrderStatus status,
                                   @Param("latestOnly") boolean latestOnly, Pageable pageable);
}
