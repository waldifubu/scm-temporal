package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.RequestComponent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RequestComponentRepository extends JpaRepository<RequestComponent, Long> {

    /**
     * Locks the request for the rest of the transaction. Its status is read, checked and then
     * written, so two reports arriving at once would otherwise both pass the same check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RequestComponent r WHERE r.id = :id")
    Optional<RequestComponent> findForUpdateById(@Param("id") Long id);

    List<RequestComponent> findBySupplierId(Long userId);
}
