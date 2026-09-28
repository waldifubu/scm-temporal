package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Component;
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

    Optional<Component> findBySku(UUID sku);

    /**
     * The components of several SKUs at once - one query for a whole request instead of one per line.
     * A SKU nothing matches is simply absent, which is what the caller reports as unknown.
     */
    List<Component> findBySkuIn(Collection<UUID> skus);

    Optional<Component> findByExternalId(String articleNo);

    boolean existsBySku(UUID sku);

    boolean existsByExternalId(String articleNo);
}
