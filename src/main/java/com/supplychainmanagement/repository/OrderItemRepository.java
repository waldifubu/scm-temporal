package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Order;
import jakarta.persistence.LockModeType;
import com.supplychainmanagement.entity.OrderItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    @EntityGraph(attributePaths = {"order", "product"})
    Optional<OrderItem> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"order", "product"})
    List<OrderItem> findAllBy();


    /**
     * Locks the line for the duration of the transaction. Packing reads how much of it is already
     * packed and then writes one more package - without the lock two concurrent calls read the same
     * figure, both find room for their quantity and both write, and the line ends up packed beyond
     * what was ordered. The unique constraint on package_item does not catch that: the two rows land
     * in different packages.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT oi FROM OrderItem oi WHERE oi.id = :id")
    Optional<OrderItem> findForUpdateById(@Param("id") Long id);
}
