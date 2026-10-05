package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Component;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.PagingAndSortingRepository;

public interface ComponentCatalogRepository extends PagingAndSortingRepository<Component, Long> {

    Slice<Component> findByNameContainingIgnoreCase(String name,
                                                      Pageable pageable);
}