package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.OrderHistory;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
