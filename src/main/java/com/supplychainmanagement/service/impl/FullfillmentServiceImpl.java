package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.fullfillment.AvailableOrderItemDto;
import com.supplychainmanagement.dto.fullfillment.ProductionResultDto;
import com.supplychainmanagement.entity.*;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.StockRepository;
import com.supplychainmanagement.repository.StorehouseRepository;
import com.supplychainmanagement.service.FullfillmentService;
import com.supplychainmanagement.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class FullfillmentServiceImpl implements FullfillmentService {

    private final StockService stockService;
    private final StorehouseRepository storehouseRepository;
    private final ProductRepository productRepository;
    private final StockRepository stockRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OrderRepository orderRepository;
    private final OrderService orderService;

    @Override
    @Transactional
    public Page<ProductionResultDto> produce(Pageable pageable) {
        Page<Product> productsPage = productRepository.findAll(pageable);
        List<ProductionResultDto> results = new ArrayList<>();

        for (Product product : productsPage.getContent()) {
            var productionResult = produceSingleProduct(product);
            if (productionResult.produced()) {
                results.add(productionResult);
            }
        }

        return new PageImpl<>(results, pageable, results.size());
    }

    private ProductionResultDto produceSingleProduct(Product product) {
        if (product.getSku() == null || product.getComponents() == null || product.getComponents().isEmpty()) {
            return new ProductionResultDto(
                    product.getName(),
                    product.getSku(),
                    null,
                    false,
                    "Missing SKU or no components configured"
            );
        }

        Map<UUID, Integer> requiredComponents = getRequiredComponents(product.getComponents());
        if (requiredComponents.isEmpty()) {
            return new ProductionResultDto(
                    product.getName(),
                    product.getSku(),
                    null,
                    false,
                    "No valid component requirements found"
            );
        }

        Long selectedStorehouseId = findEligibleStorehouse(requiredComponents);
        if (selectedStorehouseId == null) {
            return new ProductionResultDto(
                    product.getName(),
                    product.getSku(),
                    null,
                    false,
                    "Not enough components available in one storehouse"
            );
        }

        if (!canProduceInStorehouse(selectedStorehouseId, requiredComponents)) {
            return new ProductionResultDto(
                    product.getName(),
                    product.getSku(),
                    selectedStorehouseId,
                    false,
                    "Insufficient stock for one or more components"
            );
        }

        for (Map.Entry<UUID, Integer> required : requiredComponents.entrySet()) {
            Stock componentStock = stockRepository.findByStorehouseIdAndSku(selectedStorehouseId, required.getKey())
                    .orElseThrow(() -> new IllegalStateException("Stock missing for component sku " + required.getKey()));

            int decrementBy = required.getValue();
            if (componentStock.getAvailable() < decrementBy) {
                return new ProductionResultDto(
                        product.getName(),
                        product.getSku(),
                        selectedStorehouseId,
                        false,
                        "Component stock changed while producing"
                );
            }

            componentStock.setOnHand(componentStock.getOnHand() - decrementBy);
            stockRepository.save(componentStock);
        }

        stockService.add(product.getSku(), selectedStorehouseId, 1);

        return new ProductionResultDto(
                product.getName(),
                product.getSku(),
                selectedStorehouseId,
                true,
                "Production successful"
        );
    }

    private Map<UUID, Integer> getRequiredComponents(List<Component> components) {
        Map<UUID, Integer> requiredBySku = new LinkedHashMap<>();
        for (Component component : components) {
            UUID sku = component.getSku();
            if (sku == null) {
                return Collections.emptyMap();
            }
            requiredBySku.merge(sku, 1, Integer::sum);
        }
        return requiredBySku;
    }

    private Long findEligibleStorehouse(Map<UUID, Integer> requiredComponents) {
        for (Map.Entry<UUID, Integer> required : requiredComponents.entrySet()) {
            List<Stock> oldestFirstStocks = stockRepository.findAvailableBySkuOrderByUpdatedAtAsc(required.getKey());
            for (Stock stock : oldestFirstStocks) {
                Long storehouseId = stock.getStorehouse().getId();
                if (canProduceInStorehouse(storehouseId, requiredComponents)) {
                    return storehouseId;
                }
            }
        }
        return null;
    }

    private boolean canProduceInStorehouse(Long storehouseId, Map<UUID, Integer> requiredComponents) {
        for (Map.Entry<UUID, Integer> required : requiredComponents.entrySet()) {
            Stock stock = stockRepository.findByStorehouseIdAndSku(storehouseId, required.getKey()).orElse(null);
            if (stock == null || stock.getAvailable() < required.getValue()) {
                return false;
            }
        }
        return true;
    }

    private List<Storehouse> getAllStorehouses() {
        return storehouseRepository.findAll();
    }

    @Override
    public List<AvailableOrderItemDto> checkItems(Order order) {
        List<AvailableOrderItemDto> availableItems = new ArrayList<>();
        boolean allAvailable = true;
        for (OrderItem orderItem : order.getOrderItems()) {
            UUID sku = orderItem.getProduct().getSku();
            Integer requiredQuantity = orderItem.getQuantity();
            int availableQuantity = 0;
            Storehouse availableStorehouse = null;
            var isAvailable = false;

            for (Storehouse storehouse : getAllStorehouses()) {
                availableStorehouse = getAvailableStorehouse(sku, requiredQuantity);
                if (availableStorehouse != null) {
                    isAvailable = stockService.isAvailable(sku, requiredQuantity, availableStorehouse.getId());
                    availableQuantity = stockService.getAvailableQuantity(sku, availableStorehouse.getId());
                    break;
                }
            }

            availableItems.add(new AvailableOrderItemDto(orderItem.getProduct().getArticleNo(), requiredQuantity, availableQuantity, isAvailable, availableStorehouse != null ? availableStorehouse.getId() : null));
        }

        return availableItems;
    }

    private Storehouse getAvailableStorehouse(UUID sku, Integer requiredQuantity) {
        for (Storehouse storehouse : getAllStorehouses()) {
            if (stockService.isAvailable(sku, requiredQuantity, storehouse.getId())) {
                return storehouse;
            }
        }
        return null;
    }
}
