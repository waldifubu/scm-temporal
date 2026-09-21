package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    /**
     * Locks the shipment for the rest of the transaction. Changing its packages reads what it holds
     * and then writes - two concurrent calls would otherwise both pass the checks against the same,
     * stale contents.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Shipment s WHERE s.id = :id")
    Optional<Shipment> findForUpdateById(@Param("id") Long id);

    /** One page of shipments with their customer - a to-one fetch keeps the paging in SQL. */
    @EntityGraph(attributePaths = "customer")
    Page<Shipment> findAllWithCustomerBy(Pageable pageable);

    /** One page of shipments in a status, with their customer. */
    @EntityGraph(attributePaths = "customer")
    Page<Shipment> findAllWithCustomerByStatus(ShipmentStatus status, Pageable pageable);

    /**
     * The shipments of one page with their packages. Separate from the page query, because a
     * collection fetch in a paged query is paged in memory - the same split as PackageQueryServiceImpl.
     */
    @EntityGraph(attributePaths = "packages")
    List<Shipment> findWithPackagesByIdIn(Collection<Long> ids);

    /** One shipment with its customer and packages - not paged, so the collection may come along. */
    @EntityGraph(attributePaths = {"customer", "packages"})
    Optional<Shipment> findWithPackagesById(Long id);
}
