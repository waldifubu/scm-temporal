package com.supplychainmanagement.controller;


import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.service.ProductService;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/products")
@AllArgsConstructor
public class ProductController {
    private final ProductService productService;

    @GetMapping
    public Flux<Product> getProducts() {
        return productService.findAll();
    }

    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER')")
    @GetMapping("/{id}")
    public Mono<Product> getProduct(@PathVariable Long id) {
        try {
            return productService.findById(id)
                    .onErrorResume(ResourceNotFoundException.class, ignored -> productService.findByArticleNo(id));
        } catch (APIException exception) {
            return Mono.error(exception);
        }
    }

    @GetMapping("/article/{articleNo}")
    public Mono<Product> getProductByArticleNo(@PathVariable long articleNo) {
        return productService.findByArticleNo(articleNo);
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
