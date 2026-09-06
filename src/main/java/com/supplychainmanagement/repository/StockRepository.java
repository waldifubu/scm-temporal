package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Stock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * Storehouses holding at least {@code qty} unreserved units of {@code sku}, oldest stock
     * first (FEFO-style, matching {@link #findAvailableBySkuOrderByUpdatedAtAsc}).
     * <p>
     * The availability predicate {@code onHand - reserved} belongs in the WHERE clause: evaluating
     * it in Java would mean loading every candidate row and asking each storehouse separately.
     */
    @Query("""
            SELECT s FROM Stock s
            WHERE s.sku = :sku AND (s.onHand - s.reserved) >= :quantity
            ORDER BY s.updatedAt ASC
            """)
    List<Stock> findEligibleBySku(@Param("sku") UUID sku, @Param("qty") int quantity);

    List<Stock> findByStorehouseId(Long id);

    Page<Stock> findByStorehouseId(Long id, Pageable pageable);
}