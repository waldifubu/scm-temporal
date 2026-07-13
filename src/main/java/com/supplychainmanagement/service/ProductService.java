package com.supplychainmanagement.service;

import com.supplychainmanagement.entity.Product;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProductService {
    Flux<Product> findAll();

    Mono<Product> findById(Long id);

    Mono<Product> findByArticleNo(long articleNo);

    Flux<Product> searchByName(String name);

    Mono<Product> create(Product product);

    Mono<Product> update(Long id, Product product);

    Mono<Void> deleteById(Long id);
}
