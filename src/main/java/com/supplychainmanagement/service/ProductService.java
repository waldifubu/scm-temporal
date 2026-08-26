package com.supplychainmanagement.service;

import com.supplychainmanagement.entity.Product;

import java.util.List;

/**
 * Deliberately synchronous - see {@link OrderService}: with Mono/Flux return types the
 * {@code @Transactional} proxy committed before the actual database work had even started.
 */
public interface ProductService {

    List<Product> findAll();

    Product findById(Long id);

    Product findByArticleNo(long articleNo);

    List<Product> searchByName(String name);

    Product create(Product product);

    Product update(Long id, Product product);

    void deleteById(Long id);
}
