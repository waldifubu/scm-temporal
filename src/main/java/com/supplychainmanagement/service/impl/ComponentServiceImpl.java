package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.repository.ComponentRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.service.ComponentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ComponentServiceImpl implements ComponentService {
    private final ComponentRepository componentRepository;
    private final ProductRepository productRepository;

    @Override
    public Flux<Component> findAll() {
        return Mono.fromCallable(componentRepository::findAllBy)
                .flatMapMany(Flux::fromIterable)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Component> findById(Long id) {
        return Mono.fromCallable(() -> componentRepository.findWithProductById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Component", "id", id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Component> findBySku(String sku) {
        return Mono.fromCallable(() -> componentRepository.findBySku(sku)
                        .orElseThrow(() -> new APIException(HttpStatus.NOT_FOUND, "Component not found with sku: " + sku)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Component> findByArticleNo(String articleNo) {
        return Mono.fromCallable(() -> componentRepository.findByArticleNo(articleNo)
                        .orElseThrow(() -> new APIException(HttpStatus.NOT_FOUND, "Component not found with articleNo: " + articleNo)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional
    public Mono<Component> create(Component component) {
        return Mono.fromCallable(() -> {
                    validateUniqueIdentifiers(component, null);
                    bindProduct(component);
                    return componentRepository.save(component);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional
    public Mono<Component> update(Long id, Component component) {
        return Mono.fromCallable(() -> {
                    Component existingComponent = componentRepository.findById(id)
                            .orElseThrow(() -> new ResourceNotFoundException("Component", "id", id));

                    validateUniqueIdentifiers(component, existingComponent);
                    existingComponent.setManufacturer(component.getManufacturer());
                    existingComponent.setName(component.getName());
                    existingComponent.setSku(component.getSku());
                    existingComponent.setArticleNo(component.getArticleNo());
                    existingComponent.setProduct(component.getProduct());
                    bindProduct(existingComponent);

                    return componentRepository.save(existingComponent);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional
    public Mono<Void> deleteById(Long id) {
        return Mono.fromRunnable(() -> {
                    if (!componentRepository.existsById(id)) {
                        throw new ResourceNotFoundException("Component", "id", id);
                    }
                    componentRepository.deleteById(id);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void validateUniqueIdentifiers(Component component, Component existingComponent) {
        if (component.getSku() != null
                && componentRepository.existsBySku(component.getSku())
                && (existingComponent == null || !component.getSku().equals(existingComponent.getSku()))) {
            throw new APIException(HttpStatus.CONFLICT, "SKU already exists!");
        }

        if (component.getArticleNo() != null
                && componentRepository.existsByArticleNo(component.getArticleNo())
                && (existingComponent == null || !component.getArticleNo().equals(existingComponent.getArticleNo()))) {
            throw new APIException(HttpStatus.CONFLICT, "Article number already exists!");
        }
    }

    private void bindProduct(Component component) {
        Product product = component.getProduct();
        if (product == null || product.getId() == null) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Product is required!");
        }

        Product persistedProduct = productRepository.findById(product.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", product.getId()));
        component.setProduct(persistedProduct);
    }
}
