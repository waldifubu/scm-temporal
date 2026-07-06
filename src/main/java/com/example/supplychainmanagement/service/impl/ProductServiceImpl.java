package com.example.supplychainmanagement.service.impl;

import com.example.supplychainmanagement.entity.Component;
import com.example.supplychainmanagement.entity.Product;
import com.example.supplychainmanagement.exception.APIException;
import com.example.supplychainmanagement.exception.ResourceNotFoundException;
import com.example.supplychainmanagement.repository.ProductRepository;
import com.example.supplychainmanagement.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;

    @Override
    public Flux<Product> findAll() {
        return Mono.fromCallable(productRepository::findAllBy)
                .flatMapMany(Flux::fromIterable)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Product> findById(Long id) {
        return Mono.fromCallable(() -> productRepository.findWithComponentListById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Product> findByArticleNo(long articleNo) {
        return Mono.fromCallable(() -> productRepository.findByArticleNo(articleNo)
                        .orElseThrow(() -> new ResourceNotFoundException("Product", "articleNo", articleNo)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<Product> searchByName(String name) {
        return Mono.fromCallable(() -> productRepository.findByNameContainingIgnoreCase(name))
                .flatMapMany(Flux::fromIterable)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional
    public Mono<Product> create(Product product) {
        return Mono.fromCallable(() -> {
                    if (productRepository.existsByArticleNo(product.getArticleNo())) {
                        throw new APIException(HttpStatus.CONFLICT, "Article number already exists!");
                    }
                    bindComponentsToProduct(product, product.getComponentList());
                    Product savedProduct = productRepository.save(product);
                    return productRepository.findWithComponentListById(savedProduct.getId())
                            .orElseThrow(() -> new ResourceNotFoundException("Product", "id", savedProduct.getId()));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional
    public Mono<Product> update(Long id, Product product) {
        return Mono.fromCallable(() -> {
                    Product existingProduct = productRepository.findById(id)
                            .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));

                    long newArticleNo = product.getArticleNo();
                    if (newArticleNo != existingProduct.getArticleNo()
                            && productRepository.existsByArticleNo(newArticleNo)) {
                        throw new APIException(HttpStatus.CONFLICT, "Article number already exists!");
                    }

                    existingProduct.setArticleNo(newArticleNo);
                    existingProduct.setName(product.getName());
                    existingProduct.setDescription(product.getDescription());
                    existingProduct.setUnitPrice(product.getUnitPrice());
                    existingProduct.setWeight(product.getWeight());
                    existingProduct.setCategories(product.getCategories());
                    existingProduct.setComponentList(product.getComponentList());
                    bindComponentsToProduct(existingProduct, existingProduct.getComponentList());

                    Product savedProduct = productRepository.save(existingProduct);
                    return productRepository.findWithComponentListById(savedProduct.getId())
                            .orElseThrow(() -> new ResourceNotFoundException("Product", "id", savedProduct.getId()));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional
    public Mono<Void> deleteById(Long id) {
        return Mono.fromRunnable(() -> {
                    if (!productRepository.existsById(id)) {
                        throw new ResourceNotFoundException("Product", "id", id);
                    }
                    productRepository.deleteById(id);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void bindComponentsToProduct(Product product, List<Component> components) {
        if (components == null) {
            return;
        }
        for (Component component : components) {
            component.setProduct(product);
        }
    }
}
