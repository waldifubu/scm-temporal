package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Product;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface ProductDetailsRepository extends CrudRepository<Product, Long> {

    /** With the categories: the Vaadin drawer reads them after the session of this call is closed. */
    @Override
    @EntityGraph(attributePaths = "categories")
    Optional<Product> findById(Long id);
}
