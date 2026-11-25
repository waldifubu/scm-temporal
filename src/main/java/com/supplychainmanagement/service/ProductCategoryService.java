package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.product.ProductCategoryDto;
import com.supplychainmanagement.dto.product.ProductCategoryRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductCategoryService {

    /** One page of categories; the id settles the order between rows that share the sort value. */
    Page<ProductCategoryDto> findCategories(Pageable pageable);

    /** Creates a category; 409 when the name is taken. */
    ProductCategoryDto create(ProductCategoryRequest request);

    /** Changes name and description; 404 for an unknown id, 409 when another category has the name. */
    ProductCategoryDto update(Long id, ProductCategoryRequest request);

    /** Removes a category; 404 for an unknown id, 409 while a product is filed under it. */
    void delete(Long id);
}
