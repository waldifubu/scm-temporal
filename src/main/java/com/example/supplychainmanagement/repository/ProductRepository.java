package com.example.supplychainmanagement.repository;

import com.example.supplychainmanagement.entity.Product;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    @EntityGraph(attributePaths = {"componentList", "categories"})
    Optional<Product> findWithComponentListById(Long id);

    @EntityGraph(attributePaths = {"componentList", "categories"})
    List<Product> findAllBy();

    @EntityGraph(attributePaths = {"componentList", "categories"})
    Optional<Product> findByArticleNo(long articleNo);

    boolean existsByArticleNo(long articleNo);

    @EntityGraph(attributePaths = {"componentList", "categories"})
    List<Product> findByNameContainingIgnoreCase(String name);
}
