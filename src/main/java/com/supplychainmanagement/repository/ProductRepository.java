package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, Long> {
    @EntityGraph(attributePaths = {"components", "categories"})
    Optional<Product> findWithComponentsById(Long id);

    @EntityGraph(attributePaths = {"components", "categories"})
    List<Product> findAllBy();

    /**
     * One page of products alone. The components and categories are not fetched here - a collection fetch in
     * a paged query makes Hibernate page in memory - they come from {@link #findWithComponentsByIdIn}.
     */
    Page<Product> findAllBy(Pageable pageable);

    /** The products of a page with their components and categories, one query for all of them. */
    @EntityGraph(attributePaths = {"components", "categories"})
    List<Product> findWithComponentsByIdIn(Collection<Long> ids);

    @EntityGraph(attributePaths = {"components", "categories"})
    Optional<Product> findByArticleNo(long articleNo);

    boolean existsByArticleNo(long articleNo);

    @EntityGraph(attributePaths = {"components", "categories"})
    List<Product> findByNameContainingIgnoreCase(String name);

    @EntityGraph(attributePaths = {"components", "categories"})
    Optional<Product> findWithComponentsByArticleNo(Long articleNo);

    Optional<Product> findBySku(UUID sku);
}
