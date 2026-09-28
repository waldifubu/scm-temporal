package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentsRequest;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.repository.ComponentRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.RequestComponentRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.ComponentService;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ComponentServiceImpl implements ComponentService {
    private final ComponentRepository componentRepository;
    private final ProductRepository productRepository;
    private final RequestComponentRepository requestComponentRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public List<RequestComponentResponse> requestComponents(Long supplierId, RequestComponentsRequest request) {
        User user = userRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", supplierId));

        // Checked on the unproxied instance and before it is used as one: a cast up front turns any
        // other user into a ClassCastException (a 500), and a User proxy is never a Supplier. Same
        // shape as the distributor check when a shipment is handed over.
        if (!(Hibernate.unproxy(user) instanceof Supplier supplier)) {
            throw new APIException(HttpStatus.BAD_REQUEST, "User " + supplierId + " is not a supplier");
        }

        List<RequestComponentsRequest.Item> items = request.items();

        // One query for the whole request rather than one per line. The set collapses a SKU asked for
        // twice - the lines stay two, only the lookup is shared.
        Set<UUID> skus = items.stream()
                .map(RequestComponentsRequest.Item::componentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, Component> bySku = componentRepository.findBySkuIn(skus).stream()
                .collect(Collectors.toMap(Component::getSku, Function.identity()));

        List<UUID> unknown = skus.stream().filter(sku -> !bySku.containsKey(sku)).toList();
        if (!unknown.isEmpty()) {
            // Every one of them at once: placing half a list and reporting the first miss would leave
            // the caller to work out what actually went through.
            throw new APIException(HttpStatus.NOT_FOUND, "Component not found with sku: "
                    + unknown.stream().map(UUID::toString).collect(Collectors.joining(", ")));
        }

        List<RequestComponent> requests = items.stream().map(item -> {
            RequestComponent requested = new RequestComponent();
            requested.setComponent(bySku.get(item.componentId()));
            requested.setSupplier(supplier);
            requested.setQty(item.qty());
            requested.setComment(blankToNull(item.comment()));
            // requestStatus stays what the entity sets on insert - OPEN.
            return requested;
        }).toList();

        // Mapped here, while the transaction is open: the saved rows point at their component and
        // their supplier, and both are LAZY.
        return requestComponentRepository.saveAll(requests).stream()
                .map(RequestComponentResponse::from)
                .toList();
    }

    /** An empty comment is no comment - stored as null, so it stays out of the JSON. */
    private static String blankToNull(String comment) {
        return comment == null || comment.isBlank() ? null : comment.trim();
    }

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
