package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
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

public interface ShipmentPackageRepository extends JpaRepository<ShipmentPackage, Long> {
    @Query("""
        SELECT COALESCE(SUM(pi.quantity), 0)
        FROM PackageItem pi
        WHERE pi.orderItem.id = :orderItemId
    """)
    int sumQuantityByOrderItemId(
            @Param("orderItemId") Long orderItemId
    );

    /**
     * Locks the package for the rest of the transaction. Changing its contents reads what it holds
     * and then writes - two concurrent calls on the same package would otherwise both pass the
     * one-order and one-item-per-line checks against the same, stale contents.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sp FROM ShipmentPackage sp WHERE sp.id = :id")
    Optional<ShipmentPackage> findForUpdateById(@Param("id") Long id);

    /** One page of packages in a status - the packages alone, their contents come separately. */
    Page<ShipmentPackage> findAllByShipmentPackageStatus(ShipmentPackageStatus status, Pageable pageable);

    /**
     * The packages of one page with everything a list row reads: items, their order lines, the
     * products for the content weight and the orders for the due date. Separate from the page query,
     * because a collection fetch in a paged query is paged in memory - see PackageQueryServiceImpl.
     */
    @EntityGraph(attributePaths = {"items", "items.orderItem", "items.orderItem.product", "items.orderItem.order"})
    List<ShipmentPackage> findWithItemsByIdIn(Collection<Long> ids);

    Page<ShipmentPackage> findAllByShipmentPackageStatusAndPackageNumberContaining(ShipmentPackageStatus status, String packageNumber, Pageable pageable);

    /**
     * One package with its contents, fetched like {@link #findWithItemsByIdIn} - a single package is
     * not paged, so the collection can come with it in one query.
     */
    @EntityGraph(attributePaths = {"items", "items.orderItem", "items.orderItem.product", "items.orderItem.order"})
    Optional<ShipmentPackage> findWithItemsById(Long id);

    /**
     * Locks the packages about to be put into or taken out of a shipment, in ascending id order - two
     * calls locking the same packages in opposite orders would otherwise deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sp FROM ShipmentPackage sp WHERE sp.id IN :ids ORDER BY sp.id")
    List<ShipmentPackage> findAllForUpdateByIdIn(@Param("ids") Collection<Long> ids);

    /** Which customer a package goes to - one row per package and customer found through its items. */
    interface PackageCustomer {
        Long getShipmentPackageId();

        Long getCustomerId();
    }

    /**
     * The customers behind the given packages, through items, order lines and orders. A package holds
     * the items of one order, so normally one row per package; a package without items has none at
     * all - that is how an empty package shows. An order without a customer yields a null customerId.
     */
    @Query("""
        SELECT DISTINCT sp.id AS shipmentPackageId, o.customer.id AS customerId
        FROM ShipmentPackage sp
        JOIN sp.items pi
        JOIN pi.orderItem oi
        JOIN oi.order o
        WHERE sp.id IN :ids
    """)
    List<PackageCustomer> findCustomersByShipmentPackageIdIn(@Param("ids") Collection<Long> ids);
}
