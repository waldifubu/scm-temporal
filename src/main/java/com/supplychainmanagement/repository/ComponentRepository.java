package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.RequestComponent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ComponentRepository extends JpaRepository<Component, Long> {
    @EntityGraph(attributePaths = "product")
    Optional<Component> findWithProductById(Long id);

    @EntityGraph(attributePaths = "product")
    List<Component> findAllBy();

    /** The same, one page of it. The product is to-one, so the page stays cut in SQL. */
    @EntityGraph(attributePaths = "product")
    Page<Component> findAllBy(Pageable pageable);

    /** Every bill-of-materials line of one product. */
    List<Component> findByProductId(Long productId);

    Optional<Component> findBySku(UUID sku);

    /**
     * The components of several SKUs at once - one query for a whole request instead of one per line.
     * A SKU nothing matches is simply absent, which is what the caller reports as unknown.
     */
    List<Component> findBySkuIn(Collection<UUID> skus);

    Optional<Component> findByExternalId(String externalId);

    boolean existsBySku(UUID sku);

    boolean existsByExternalId(String externalId);
}
