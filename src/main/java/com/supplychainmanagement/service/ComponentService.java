package com.supplychainmanagement.service;

import com.supplychainmanagement.entity.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ComponentService {
    Flux<Component> findAll();

    Mono<Component> findById(Long id);

    Mono<Component> findBySku(String sku);

    Mono<Component> findByArticleNo(String articleNo);

    Mono<Component> create(Component component);

    Mono<Component> update(Long id, Component component);

    Mono<Void> deleteById(Long id);
}
