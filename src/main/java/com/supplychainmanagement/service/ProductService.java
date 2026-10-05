package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.product.ProductRequestDto;
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

    /**
     * Creates a product from what a client may send - see {@link ProductRequestDto}. A component
     * line carrying an id is a 400: a product that does not exist yet has no existing lines, and
     * such an id used to reassign another product's component through the cascade.
     */
    Product create(ProductRequestDto request);

    /**
     * Changes a product. The id comes from the path, never from the body, and a component line with
     * an id has to be a line of this product (400 otherwise).
     */
    Product update(Long id, ProductRequestDto request);

    void deleteById(Long id);

    Product findBySku(String sku);
}
