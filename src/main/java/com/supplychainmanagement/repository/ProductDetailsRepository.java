package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Product;
import org.springframework.data.repository.CrudRepository;

public interface ProductDetailsRepository extends CrudRepository<Product, Long> {
}