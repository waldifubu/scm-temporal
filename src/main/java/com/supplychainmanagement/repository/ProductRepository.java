package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Product;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    @EntityGraph(attributePaths = {"components", "categories"})
    Optional<Product> findWithComponentsById(Long id);

    @EntityGraph(attributePaths = {"components", "categories"})
    List<Product> findAllBy();

    @EntityGraph(attributePaths = {"components", "categories"})
    Optional<Product> findByArticleNo(long articleNo);

    boolean existsByArticleNo(long articleNo);

    @EntityGraph(attributePaths = {"components", "categories"})
    List<Product> findByNameContainingIgnoreCase(String name);
}
