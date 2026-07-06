package com.example.supplychainmanagement.controller;


import com.example.supplychainmanagement.entity.Product;
import com.example.supplychainmanagement.exception.ResourceNotFoundException;
import com.example.supplychainmanagement.service.ProductService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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

    @GetMapping("/{id}")
    public Mono<Product> getProduct(@PathVariable Long id) {
        return productService.findById(id)
                .onErrorResume(ResourceNotFoundException.class, ignored -> productService.findByArticleNo(id));
    }

    @GetMapping("/article/{articleNo}")
    public Mono<Product> getProductByArticleNo(@PathVariable long articleNo) {
        return productService.findByArticleNo(articleNo);
    }

    @PostMapping
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
