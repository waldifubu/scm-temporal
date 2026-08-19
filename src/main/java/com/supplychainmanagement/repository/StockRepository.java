package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockRepository extends JpaRepository<Stock, Long> {
    @Query("SELECT s FROM Stock s WHERE s.sku = :sku")
    Optional<Stock> findBySkuForUpdate(@Param("sku") UUID sku);

    Optional<Stock> findByStorehouseIdAndSku(
            Long storehouseId,
            UUID sku
    );

    List<Stock> findBySku(UUID sku);

    @Query("SELECT s FROM Stock s WHERE s.sku = :sku AND s.onHand > s.reserved ORDER BY s.updatedAt ASC")
    List<Stock> findAvailableBySkuOrderByUpdatedAtAsc(@Param("sku") UUID sku);
}