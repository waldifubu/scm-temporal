package com.supplychainmanagement.controller;


import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.service.ProductService;
import com.supplychainmanagement.service.RoleService;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping({"/api/{version}/products"})
@AllArgsConstructor
public class ProductController {
    private final ProductService productService;
    private final RoleService roleService;

    @GetMapping
    public Flux<Product> getProducts() {
        return productService.findAll();
    }

    @GetMapping(value = "/{articleNo}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'CUSTOMER', 'MANAGER', 'WAREHOUSE')")
    public Mono<Product> getProductByArticleNo(@PathVariable long articleNo,
                                               @AuthenticationPrincipal org.springframework.security.core.userdetails.User authUser) {
        try {
            boolean isPrivilegedUser = roleService.isPrivilegedUser(authUser);
            return productService.findByArticleNo(articleNo)
                    .map(product -> showProducts(product, isPrivilegedUser));
        } catch (APIException exception) {
            return Mono.error(exception);
        }
    }

    private Product showProducts(Product product, boolean isPrivilegedUser) {
        if (!isPrivilegedUser) {
            product.setCategories(null);
            product.setId(null);
            product.setComponents(null); // @JsonInclude(NON_NULL) drops the fields from the JSON
        }
        return product;
    }

    @PostMapping("")
    public Mono<Product> createProduct(@RequestBody Product product) {
        return productService.create(product);
    }

    @PutMapping("/{id}")
    public Mono<Product> updateProduct(@PathVariable Long id, @RequestBody Product product) {
        return productService.update(id, product);
    }

    @DeleteMapping("/{id}")
    public Mono<Void> deleteProduct(@PathVariable Long id) {
        return productService.deleteById(id);
    }
}
