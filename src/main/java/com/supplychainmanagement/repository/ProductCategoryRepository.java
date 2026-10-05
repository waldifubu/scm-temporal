package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.ProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Categories by id, for resolving what {@code ProductRequestDto.categoryIds} names.
 * <p>
 * There was none: categories arrived as whole {@code ProductCategory} objects in the request body
 * and were attached as they came, so nothing checked that they existed and a client could invent
 * one - with a {@code Set<Product>} inside it.
 */
public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {
}
