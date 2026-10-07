package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.ProductCategory;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.dto.product.ProductRequestDto;
import com.supplychainmanagement.repository.ProductCategoryRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;

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
    public Product create(ProductRequestDto request) {
        if (productRepository.existsByArticleNo(request.articleNo())) {
            throw new APIException(HttpStatus.CONFLICT, "Article number already exists!");
        }

        // A fresh entity: a request cannot carry an id and turn the save into a merge over an
        // existing product, which is what taking the entity as the body allowed.
        Product product = new Product();
        applyProductData(request, product);
        product.setComponents(new ArrayList<>(componentsFor(request, product, List.of())));
        applyCategories(product, categoriesFor(request.categoryIds()));

        Product savedProduct = productRepository.save(product);
        return productRepository.findWithComponentsById(savedProduct.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", savedProduct.getId()));
    }

    /** The product's own fields. {@code sku} and {@code active} only when they are sent. */
    private void applyProductData(ProductRequestDto request, Product product) {
        product.setArticleNo(request.articleNo());
        product.setName(request.name());
        product.setDescription(request.description());
        product.setUnitPrice(request.unitPrice());
        product.setWeight(request.weight());
        if (request.sku() != null) {
            product.setSku(request.sku());
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }
    }

    /**
     * The categories the request names, looked up rather than taken from the body. An unknown id is a
     * 404 naming every one of them, so a caller does not have to guess which of a list was wrong.
     * {@code null} means "leave the categories alone" and is passed through as null.
     */
    private Set<ProductCategory> categoriesFor(Set<Long> categoryIds) {
        if (categoryIds == null) {
            return null;
        }
        if (categoryIds.isEmpty()) {
            return new LinkedHashSet<>();
        }

        List<ProductCategory> found = productCategoryRepository.findAllById(categoryIds);
        if (found.size() != categoryIds.size()) {
            Set<Long> known = found.stream().map(ProductCategory::getId).collect(Collectors.toSet());
            String missing = categoryIds.stream()
                    .filter(id -> !known.contains(id))
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));
            // Every missing id at once rather than the first - ResourceNotFoundException only takes a
            // Long, so the list goes into the message through APIException.
            throw new APIException(HttpStatus.NOT_FOUND, "ProductCategory not found with id: " + missing);
        }
        return new LinkedHashSet<>(found);
    }

    /**
     * Turns the request's bill-of-materials lines into components of this product.
     * <p>
     * This is where the hole was: a line used to arrive as a {@code Component} entity, and with the
     * id of a component belonging to another product the {@code cascade = ALL} on
     * {@code Product.components} reassigned that row to this product - rewriting a foreign bill of
     * materials, with nothing in the request saying so. An id is now only accepted when it is one of
     * <strong>this</strong> product's own lines; anything else is a 400 rather than a silent move.
     *
     * @param existing the product's current lines - empty on create, which is why an id is refused
     *                 there
     */
    private List<Component> componentsFor(ProductRequestDto request, Product product,
                                          List<Component> existing) {
        if (request.components() == null) {
            return null;
        }

        Map<Long, Component> ownById = existing.stream()
                .filter(component -> component.getId() != null)
                .collect(Collectors.toMap(Component::getId, Function.identity(), (first, same) -> first));

        List<Component> lines = new ArrayList<>(request.components().size());
        for (ProductRequestDto.ComponentLine line : request.components()) {
            Component target;
            if (line.id() == null) {
                target = new Component();
            } else {
                target = ownById.get(line.id());
                if (target == null) {
                    throw new APIException(HttpStatus.BAD_REQUEST, "Component " + line.id()
                            + " is not a component of product " + request.articleNo()
                            + " - leave the id out to add a new one");
                }
            }

            target.setName(line.name());
            target.setManufacturer(line.manufacturer());
            target.setArticleNo(line.articleNo());
            target.setDescription(line.description());
            target.setExternalId(line.externalId());
            if (line.weight() != null) {
                target.setWeight(line.weight());
            }
            if (line.sku() != null) {
                target.setSku(line.sku());
            }
            if (line.qty() != null) {
                target.setQty(line.qty());
            }
            target.setProduct(product);
            lines.add(target);
        }
        return lines;
    }

    @Override
    @Transactional
    public Product update(Long id, ProductRequestDto request) {
        Product existingProduct = productRepository.findByArticleNo(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));

        long newArticleNo = request.articleNo();
        if (newArticleNo != existingProduct.getArticleNo()
                && productRepository.existsByArticleNo(newArticleNo)) {
            throw new APIException(HttpStatus.CONFLICT, "Article number already exists!");
        }

        applyProductData(request, existingProduct);
        applyCategories(existingProduct, categoriesFor(request.categoryIds()));
        // Read with its components above, so the ids in the request can be checked against the
        // product's own lines before anything is attached.
        applyComponents(existingProduct, componentsFor(request, existingProduct,
                existingProduct.getComponents() == null ? List.of() : existingProduct.getComponents()));

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

    @Override
    public Product findBySku(String sku) {
        return productRepository.findBySku(UUID.fromString(sku))
                .orElseThrow(() -> new ResourceNotFoundException("Product", "sku", 0L));
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
