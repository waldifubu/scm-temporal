package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.ShipmentPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipmentPackageRepository extends JpaRepository<ShipmentPackage, Long> {
    @Query("""
        SELECT COALESCE(SUM(pi.quantity), 0)
        FROM PackageItem pi
        WHERE pi.orderItem.id = :orderItemId
    """)
    int sumQuantityByOrderItemId(
            @Param("orderItemId") Long orderItemId
    );
}