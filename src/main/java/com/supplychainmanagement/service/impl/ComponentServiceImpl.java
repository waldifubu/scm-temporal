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

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ComponentServiceImpl implements ComponentService {
    private final ComponentRepository componentRepository;
    private final ProductRepository productRepository;

    @Override
    public List<Component> findAll() {
        return componentRepository.findAllBy();
    }

    @Override
    public Component findById(Long id) {
        return componentRepository.findWithProductById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Component", "id", id));
    }

    @Override
    public Component findBySku(UUID sku) {
        return componentRepository.findBySku(sku)
                .orElseThrow(() -> new APIException(HttpStatus.NOT_FOUND, "Component not found with sku: " + sku.toString()));
    }

    @Override
    public Component findByArticleNo(String articleNo) {
        return componentRepository.findByExternalId(articleNo)
                .orElseThrow(() -> new APIException(HttpStatus.NOT_FOUND, "Component not found with articleNo: " + articleNo));
    }

    @Override
    @Transactional
    public Component create(Component component) {
        validateUniqueIdentifiers(component, null);
        bindProduct(component);
        return componentRepository.save(component);
    }

    @Override
    @Transactional
    public Component update(Long id, Component component) {
        Component existingComponent = componentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Component", "id", id));

        validateUniqueIdentifiers(component, existingComponent);
        existingComponent.setManufacturer(component.getManufacturer());
        existingComponent.setName(component.getName());
        existingComponent.setSku(component.getSku());
        existingComponent.setExternalId(component.getExternalId());
        existingComponent.setProduct(component.getProduct());
        bindProduct(existingComponent);

        return componentRepository.save(existingComponent);
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        if (!componentRepository.existsById(id)) {
            throw new ResourceNotFoundException("Component", "id", id);
        }
        componentRepository.deleteById(id);
    }

    private void validateUniqueIdentifiers(Component component, Component existingComponent) {
        if (component.getSku() != null
                && componentRepository.existsBySku(component.getSku())
                && (existingComponent == null || !component.getSku().equals(existingComponent.getSku()))) {
            throw new APIException(HttpStatus.CONFLICT, "SKU already exists!");
        }

        if (component.getExternalId() != null
                && componentRepository.existsByExternalId(component.getExternalId())
                && (existingComponent == null || !component.getExternalId().equals(existingComponent.getExternalId()))) {
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
