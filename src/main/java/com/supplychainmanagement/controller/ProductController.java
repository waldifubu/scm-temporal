package com.supplychainmanagement.controller;


import com.supplychainmanagement.dto.mapper.ProductMapper;
import com.supplychainmanagement.dto.product.ProductDto;
import com.supplychainmanagement.dto.product.ProductRequestDto;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.service.ProductService;
import com.supplychainmanagement.service.RoleService;
import jakarta.validation.Valid;
import com.supplychainmanagement.dto.common.PageResponse;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping({"/api/{version}/products"})
@AllArgsConstructor
public class ProductController {
    private final ProductService productService;
    private final RoleService roleService;
    private final ProductMapper productMapper;

    /** What a product list can be sorted by: the product's own columns, anything else is a 400. */
    static final Set<String> PRODUCT_SORTABLE = Set.of("id", "articleNo", "name", "unitPrice", "weight", "createdAt",
            "updatedAt", "active");

    /**
     * The products, paged like every list: {@code page} / {@code size} / {@code sort} / {@code order},
     * answered as {@code {content, total, page, size}}. Privileged callers get the components and categories
     * of each product, the others the public view.
     */
    @GetMapping(version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'CUSTOMER', 'MANAGER', 'WAREHOUSE')")
    public PageResponse<ProductDto> getProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "ASC") String order,
            @AuthenticationPrincipal org.springframework.security.core.userdetails.User authUser) {
        boolean isPrivilegedUser = roleService.isPrivilegedUser(authUser);
        var pageable = PageRequests.of(page, size, sort, order, PRODUCT_SORTABLE);
        return PageResponse.of(productService.findPage(pageable, isPrivilegedUser)
                .map(product -> productMapper.mapToDto(product, isPrivilegedUser)));
    }

    @GetMapping(value = "/sku/{sku}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'CUSTOMER', 'MANAGER', 'WAREHOUSE')")
    public ProductDto getProductBySku(@PathVariable String sku,
                                            @AuthenticationPrincipal org.springframework.security.core.userdetails.User authUser) {
        boolean isPrivilegedUser = roleService.isPrivilegedUser(authUser);
        return productMapper.mapToDto(productService.findBySku(sku), isPrivilegedUser);
    }

    @GetMapping(value = "/{articleNo}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'CUSTOMER', 'MANAGER', 'WAREHOUSE')")
    public ProductDto getProductByArticleNo(@PathVariable long articleNo,
                                            @AuthenticationPrincipal org.springframework.security.core.userdetails.User authUser) {
        boolean isPrivilegedUser = roleService.isPrivilegedUser(authUser);
        return productMapper.mapToDto(productService.findByArticleNo(articleNo), isPrivilegedUser);
    }

    @PostMapping(value = "", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public ProductDto createProduct(@Valid @RequestBody ProductRequestDto product) {
        return productMapper.mapToDto(productService.create(product));
    }

    @PutMapping(value = "/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public ProductDto updateProduct(@PathVariable Long id,
                                    @Valid @RequestBody ProductRequestDto product) {
        return productMapper.mapToDto(productService.update(id, product));
    }

    @DeleteMapping(value = "/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public void deleteProduct(@PathVariable Long id) {
        productService.deleteById(id);
    }
}
