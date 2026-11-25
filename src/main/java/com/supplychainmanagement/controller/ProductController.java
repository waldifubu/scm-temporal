package com.supplychainmanagement.controller;


import com.supplychainmanagement.dto.mapper.ProductMapper;
import com.supplychainmanagement.dto.product.ProductDto;
import com.supplychainmanagement.dto.product.ProductRequestDto;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.model.common.PageRequests;
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
     * of each product, the warehouse the categories, the others the public view.
     */
    @GetMapping(version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'CUSTOMER', 'MANAGER', 'WAREHOUSE', 'LOGISTICS')")
    public PageResponse<ProductDto> getProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "ASC") String order,
            @AuthenticationPrincipal org.springframework.security.core.userdetails.User authUser) {
        var pageable = PageRequests.of(page, size, sort, order, PRODUCT_SORTABLE);
        // Anybody who is shown more than the product's own columns needs them fetched with the page.
        boolean withDetails = roleService.isPrivilegedUser(authUser) || seesCategoriesOnly(authUser);
        return PageResponse.of(productService.findPage(pageable, withDetails)
                .map(product -> view(product, authUser)));
    }

    @GetMapping(value = "/sku/{sku}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'CUSTOMER', 'MANAGER', 'WAREHOUSE', 'LOGISTICS')")
    public ProductDto getProductBySku(@PathVariable String sku,
                                            @AuthenticationPrincipal org.springframework.security.core.userdetails.User authUser) {
        return view(productService.findBySku(sku), authUser);
    }

    @GetMapping(value = "/{articleNo}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'CUSTOMER', 'MANAGER', 'WAREHOUSE', 'LOGISTICS')")
    public ProductDto getProductByArticleNo(@PathVariable long articleNo,
                                            @AuthenticationPrincipal org.springframework.security.core.userdetails.User authUser) {
        return view(productService.findByArticleNo(articleNo), authUser);
    }

    /** The product as this caller may see it: ADMIN/MANAGER everything, WAREHOUSE categories too, others less. */
    private ProductDto view(Product product, org.springframework.security.core.userdetails.User authUser) {
        if (seesCategoriesOnly(authUser)) {
            return productMapper.mapToWarehouseDto(product);
        }
        return productMapper.mapToDto(product, roleService.isPrivilegedUser(authUser));
    }

    /** A warehouse user who is not also ADMIN or MANAGER. */
    private boolean seesCategoriesOnly(org.springframework.security.core.userdetails.User authUser) {
        return !roleService.isPrivilegedUser(authUser) && roleService.hasAnyAuthority(authUser, RoleEnum.WAREHOUSE);
    }

    @PostMapping(value = "", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public ProductDto createProduct(@Valid @RequestBody ProductRequestDto product) {
        return productMapper.mapToDto(productService.create(product));
    }

    @PutMapping(value = "/{articleNo}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public ProductDto updateProduct(@PathVariable long articleNo,
                                    @Valid @RequestBody ProductRequestDto product) {
        return productMapper.mapToDto(productService.update(articleNo, product));
    }

    @DeleteMapping(value = "/{articleNo}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public void deleteProduct(@PathVariable long articleNo) {
        productService.deleteByArticleNo(articleNo);
    }
}
