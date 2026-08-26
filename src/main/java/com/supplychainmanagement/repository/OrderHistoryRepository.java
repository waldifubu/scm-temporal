package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.OrderHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderHistoryRepository extends JpaRepository<OrderHistory, Long> {
    List<OrderHistory> findByOrderId(Long orderId);

    List<OrderHistory> findByUserId(Long userId);
}
