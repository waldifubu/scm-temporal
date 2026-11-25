package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.product.ProductCategoryDto;
import com.supplychainmanagement.dto.product.ProductCategoryRequest;
import com.supplychainmanagement.model.common.PageRequests;
import com.supplychainmanagement.service.ProductCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** The product categories, administered by ADMIN. */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}/categories"})
public class ProductCategoryController {

    /** What {@code sort} may name: the category's own columns, anything else is a 400. */
    static final Set<String> CATEGORY_SORTABLE = Set.of("id", "name", "description");

    private final ProductCategoryService productCategoryService;

    /** All categories, paged like every list; sorted by name unless asked otherwise. */
    @GetMapping(path = "", version = "1.0")
    @PreAuthorize("hasAuthority('ADMIN')")
    public PageResponse<ProductCategoryDto> getCategories(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(productCategoryService.findCategories(
                PageRequests.of(page, size, sort, order, CATEGORY_SORTABLE)));
    }

    /** Creates a category; 201 with the stored row, 400 for a blank name, 409 for one that is taken. */
    @PostMapping(path = "", version = "1.0")
    @PreAuthorize("hasAuthority('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductCategoryDto createCategory(@Valid @RequestBody ProductCategoryRequest request) {
        return productCategoryService.create(request);
    }

    /** Changes name and description of one category; 404, 400 and 409 as for creating. */
    @PutMapping(path = "/{id}", version = "1.0")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ProductCategoryDto updateCategory(@PathVariable Long id,
                                             @Valid @RequestBody ProductCategoryRequest request) {
        return productCategoryService.update(id, request);
    }

    /** Removes a category no product is filed under; 204, 404 for an unknown id, 409 while it is used. */
    @DeleteMapping(path = "/{id}", version = "1.0")
    @PreAuthorize("hasAuthority('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable Long id) {
        productCategoryService.delete(id);
    }
}
