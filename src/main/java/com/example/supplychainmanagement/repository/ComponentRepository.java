package com.example.supplychainmanagement.repository;

import com.example.supplychainmanagement.entity.Component;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ComponentRepository extends JpaRepository<Component, Long> {
    @EntityGraph(attributePaths = "product")
    Optional<Component> findWithProductById(Long id);

    @EntityGraph(attributePaths = "product")
    List<Component> findAllBy();

    Optional<Component> findBySku(String sku);

    Optional<Component> findByArticleNo(String articleNo);

    boolean existsBySku(String sku);

    boolean existsByArticleNo(String articleNo);
}
