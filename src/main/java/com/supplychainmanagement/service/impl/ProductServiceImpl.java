package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.ProductCategory;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;

    @Override
    public List<Product> findAll() {
        return productRepository.findAllBy();
    }

    @Override
    public Product findById(Long id) {
        return productRepository.findWithComponentsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));
    }

    @Override
    public Product findByArticleNo(long articleNo) {
        return productRepository.findByArticleNo(articleNo)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "articleNo", articleNo));
    }

    @Override
    public List<Product> searchByName(String name) {
        return productRepository.findByNameContainingIgnoreCase(name);
    }

    @Override
    @Transactional
    public Product create(Product product) {
        if (productRepository.existsByArticleNo(product.getArticleNo())) {
            throw new APIException(HttpStatus.CONFLICT, "Article number already exists!");
        }
        bindComponentsToProduct(product, product.getComponents());
        Product savedProduct = productRepository.save(product);
        return productRepository.findWithComponentsById(savedProduct.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", savedProduct.getId()));
    }

    @Override
    @Transactional
    public Product update(Long id, Product product) {
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
        applyCategories(existingProduct, product.getCategories());
        applyComponents(existingProduct, product.getComponents());

        Product savedProduct = productRepository.save(existingProduct);
        return productRepository.findWithComponentsById(savedProduct.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", savedProduct.getId()));
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        if (!productRepository.existsById(id)) {
            throw new ResourceNotFoundException("Product", "id", id);
        }
        productRepository.deleteById(id);
    }

    /**
     * Applies the incoming components to the existing product.
     * <p>
     * Deliberately NOT via {@code existingProduct.setComponents(...)}: {@code Product.components} is
     * mapped with {@code orphanRemoval = true}. Now that {@code update} really runs inside a
     * transaction, {@code existingProduct} is managed - replacing the managed collection instance
     * with a different one makes Hibernate fail the flush with "A collection with
     * cascade=all-delete-orphan was no longer referenced". Same approach as
     * OrderServiceImpl.applyOrderItems.
     */
    private void applyComponents(Product existingProduct, List<Component> incomingComponents) {
        if (incomingComponents == null) {
            return;
        }

        if (existingProduct.getComponents() == null) {
            existingProduct.setComponents(new ArrayList<>());
        }
        List<Component> currentComponents = existingProduct.getComponents();

        Map<Long, Component> currentById = new HashMap<>();
        for (Component currentComponent : currentComponents) {
            if (currentComponent.getId() != null) {
                currentById.put(currentComponent.getId(), currentComponent);
            }
        }

        List<Component> mergedComponents = new ArrayList<>(incomingComponents.size());
        for (Component incomingComponent : incomingComponents) {
            Component target = incomingComponent.getId() != null
                    ? currentById.get(incomingComponent.getId())
                    : null;

            if (target == null) {
                target = incomingComponent;
            } else {
                target.setArticleNo(incomingComponent.getArticleNo());
                target.setName(incomingComponent.getName());
                target.setDescription(incomingComponent.getDescription());
                target.setWeight(incomingComponent.getWeight());
                if (incomingComponent.getSku() != null) {
                    target.setSku(incomingComponent.getSku());
                }
            }

            target.setProduct(existingProduct);
            mergedComponents.add(target);
        }

        currentComponents.clear();
        currentComponents.addAll(mergedComponents);
    }

    /**
     * Unlike {@code components}, the collection is replaced directly here: the ManyToMany
     * association has no orphanRemoval, so the "all-delete-orphan" failure cannot occur. An
     * in-place synchronisation would in fact be wrong - {@link ProductCategory} defines no
     * equals/hashCode, so a set comparison would run on object identity and discard every
     * existing category.
     */
    private void applyCategories(Product existingProduct, Set<ProductCategory> incomingCategories) {
        if (incomingCategories == null) {
            return;
        }
        existingProduct.setCategories(incomingCategories);
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
