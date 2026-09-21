package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.PackageItem;
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

public interface PackageItemRepository extends JpaRepository<PackageItem, Long> {

    /**
     * Locks the items, in ascending id order. Without the lock two requests could put the same
     * loose item into two different packages; the fixed order keeps two requests locking an
     * overlapping set from deadlocking each other. Never call it with an empty collection - an
     * empty IN is not valid SQL.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pi FROM PackageItem pi WHERE pi.id IN :ids ORDER BY pi.id")
    List<PackageItem> findAllForUpdateByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * Every package item, loose or in a package, with the order line, product and order a list row
     * reads. All three are to-one associations, so fetching them keeps the paging in SQL - unlike a
     * collection.
     */
    @EntityGraph(attributePaths = {"orderItem", "orderItem.product", "orderItem.order"})
    Page<PackageItem> findAllWithProductBy(Pageable pageable);

    /**
     * The loose package items - packed from POST /packing but not yet put into a package. Same
     * fetch as {@link #findAllWithProductBy}, so the paging stays in SQL.
     */
    @EntityGraph(attributePaths = {"orderItem", "orderItem.product", "orderItem.order"})
    Page<PackageItem> findAllWithProductByShipmentPackageIsNull(Pageable pageable);

    /** One package item with what its detail view reads - order line, product and order. */
    @EntityGraph(attributePaths = {"orderItem", "orderItem.product", "orderItem.order"})
    Optional<PackageItem> findWithProductById(Long id);

    /** One package item id with the order line it belongs to. */
    interface ItemOfLine {
        Long getOrderItemId();

        Long getPackageItemId();
    }

    /**
     * Every package item of the given order lines, loose or in a package, as bare ids in ascending
     * order - what PackageItemResponse.siblings is computed from. Never call it with an empty
     * collection - an empty IN is not valid SQL.
     */
    @Query("""
            SELECT pi.orderItem.id AS orderItemId, pi.id AS packageItemId
            FROM PackageItem pi
            WHERE pi.orderItem.id IN :orderItemIds
            ORDER BY pi.id
            """)
    List<ItemOfLine> findItemIdsByOrderItemIdIn(@Param("orderItemIds") Collection<Long> orderItemIds);
}
