package com.supplychainmanagement.controller;


import com.supplychainmanagement.dto.mapper.ProductMapper;
import com.supplychainmanagement.dto.product.ProductDto;
import com.supplychainmanagement.dto.product.ProductRequestDto;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.service.ProductService;
import com.supplychainmanagement.service.RoleService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/api/{version}/products"})
@AllArgsConstructor
public class ProductController {
    private final ProductService productService;
    private final RoleService roleService;
    private final ProductMapper productMapper;

    @GetMapping(version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'CUSTOMER', 'MANAGER', 'WAREHOUSE')")
    public List<ProductDto> getProducts(@AuthenticationPrincipal org.springframework.security.core.userdetails.User authUser) {
        boolean isPrivilegedUser = roleService.isPrivilegedUser(authUser);
        return productService.findAll().stream()
                .map(product -> productMapper.mapToDto(product, isPrivilegedUser))
                .toList();
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
