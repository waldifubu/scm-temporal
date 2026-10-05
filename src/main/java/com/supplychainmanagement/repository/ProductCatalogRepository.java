package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Product;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.PagingAndSortingRepository;

public interface ProductCatalogRepository extends PagingAndSortingRepository<Product, Long> {

    Slice<Product> findByNameContainingIgnoreCase(String name,
                                                  Pageable pageable);
}
