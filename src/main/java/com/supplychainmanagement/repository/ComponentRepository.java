package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Component;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ComponentRepository extends JpaRepository<Component, Long> {
    @EntityGraph(attributePaths = "product")
    Optional<Component> findWithProductById(Long id);

    @EntityGraph(attributePaths = "product")
    List<Component> findAllBy();

    Optional<Component> findBySku(UUID sku);

    Optional<Component> findByExternalId(String articleNo);

    boolean existsBySku(UUID sku);

    boolean existsByExternalId(String articleNo);
}
