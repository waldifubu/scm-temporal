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
     * The shipments that carry packages with items of this order, each once, oldest first, with the distributor
     * (a left fetch - a shipment nobody has taken on yet has none). Reached over the packages, because an order has
     * no reference to a shipment.
     */
    @Query("""
            select distinct s
            from ShipmentPackage sp
              join sp.shipment s
              join sp.items pi
              join pi.orderItem oi
              left join fetch s.distributor
            where oi.order.id = :orderId
            order by s.id
            """)
    List<Shipment> findByOrderId(@Param("orderId") Long orderId);

    /** Shipment id, package number and package id of every package in a shipment that holds items of this order. */
    @Query("""
            select distinct sp.shipment.id, sp.packageNumber, sp.id
            from ShipmentPackage sp
              join sp.items pi
              join pi.orderItem oi
            where oi.order.id = :orderId and sp.shipment is not null
            order by sp.id
            """)
    List<Object[]> findPackageNumbersByOrderId(@Param("orderId") Long orderId);

    /**
     * Locks the shipment for the rest of the transaction. Changing its packages reads what it holds
     * and then writes - two concurrent calls would otherwise both pass the checks against the same,
     * stale contents.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Shipment s WHERE s.id = :id")
    Optional<Shipment> findForUpdateById(@Param("id") Long id);

    /**
     * One page of shipments with their customer and distributor - to-one fetches keep the paging in SQL.
     * The distributor is optional, so it is a left join: a shipment nobody has taken on is still listed,
     * also when the page is sorted by the distributor's name.
     */
    @EntityGraph(attributePaths = {"customer", "distributor"})
    Page<Shipment> findAllWithCustomerBy(Pageable pageable);

    /** One page of shipments in a status, with their customer and distributor. */
    @EntityGraph(attributePaths = {"customer", "distributor"})
    Page<Shipment> findAllWithCustomerByStatus(ShipmentStatus status, Pageable pageable);

    /** One page of the shipments assigned to one distributor, with their customer. */
    @EntityGraph(attributePaths = "customer")
    Page<Shipment> findAllWithCustomerByDistributorId(Long distributorId, Pageable pageable);

    /** The same, narrowed to one status. */
    @EntityGraph(attributePaths = "customer")
    Page<Shipment> findAllWithCustomerByDistributorIdAndStatus(Long distributorId, ShipmentStatus status,
                                                               Pageable pageable);

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
