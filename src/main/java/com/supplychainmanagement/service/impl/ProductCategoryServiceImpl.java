package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.mapper.ProductMapper;
import com.supplychainmanagement.dto.product.ProductCategoryDto;
import com.supplychainmanagement.dto.product.ProductCategoryRequest;
import com.supplychainmanagement.entity.ProductCategory;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.repository.ProductCategoryRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.service.ProductCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductCategoryServiceImpl implements ProductCategoryService {

    private final ProductCategoryRepository productCategoryRepository;
    private final ProductMapper productMapper;
    private final ProductRepository productRepository;

    /** The {@code products} of a category are never read, so the list is one query (and the count). */
    @Override
    @Transactional(readOnly = true)
    public Page<ProductCategoryDto> findCategories(Pageable pageable) {
        Pageable settled = pageable.getSort().getOrderFor("id") != null ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        pageable.getSort().and(Sort.by("id")));
        return productCategoryRepository.findAll(settled).map(productMapper::mapToDto);
    }

    @Override
    @Transactional
    public ProductCategoryDto create(ProductCategoryRequest request) {
        String name = request.name().trim();
        if (productCategoryRepository.existsByName(name)) {
            throw nameTaken(name);
        }
        return save(new ProductCategory(), name, request.description());
    }

    @Override
    @Transactional
    public ProductCategoryDto update(Long id, ProductCategoryRequest request) {
        ProductCategory category = find(id);
        String name = request.name().trim();
        if (productCategoryRepository.existsByNameAndIdNot(name, id)) {
            throw nameTaken(name);
        }
        return save(category, name, request.description());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ProductCategory category = find(id);
        long products = productRepository.countByCategoriesId(id);
        if (products > 0) {
            throw new APIException(HttpStatus.CONFLICT, "Category " + category.getName() + " is used by "
                    + products + (products == 1 ? " product" : " products") + " and cannot be deleted");
        }
        try {
            productCategoryRepository.delete(category);
            // Flushed inside the try: a product filed under it in the meantime would otherwise surface at the
            // commit, after this method, as a 500.
            productCategoryRepository.flush();
        } catch (DataIntegrityViolationException stillUsed) {
            throw new APIException(HttpStatus.CONFLICT,
                    "Category " + category.getName() + " is used by a product and cannot be deleted");
        }
    }

    private ProductCategory find(Long id) {
        return productCategoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductCategory", "id", id));
    }

    private ProductCategoryDto save(ProductCategory category, String name, String description) {
        category.setName(name);
        category.setDescription(description == null || description.isBlank() ? null : description.trim());
        try {
            return productMapper.mapToDto(productCategoryRepository.saveAndFlush(category));
        } catch (DataIntegrityViolationException taken) {
            // Two requests with the same name passed the check together: the unique column decides.
            throw nameTaken(name);
        }
    }

    private static APIException nameTaken(String name) {
        return new APIException(HttpStatus.CONFLICT, "A category named " + name + " already exists");
    }
}
