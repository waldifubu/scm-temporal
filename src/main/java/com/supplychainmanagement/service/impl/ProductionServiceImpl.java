package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.fullfillment.AvailableOrderItemDto;
import com.supplychainmanagement.dto.fullfillment.ProductionResultDto;
import com.supplychainmanagement.entity.*;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.StockRepository;
import com.supplychainmanagement.service.ProductionService;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@AllArgsConstructor
public class ProductionServiceImpl implements ProductionService {

    final StockService stockService;
    final StockRepository stockRepository;
    final ProductRepository productRepository;

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

    /**
     * Reports, per order line, whether a single storehouse can cover it - without reserving and
     * without writing. Callers that want to change state have to do so themselves; this method is
     * reachable as a plain query through POST /orders/{orderNo}/check.
     */
    @Override
    @Transactional(readOnly = true)
    public List<AvailableOrderItemDto> checkItems(Order order) {
        List<AvailableOrderItemDto> availableItems = new ArrayList<>();

        for (OrderItem orderItem : order.getOrderItems()) {
            if (orderItem.getFulfillmentStatus() != null && orderItem.getFulfillmentStatus() != FulfillmentStatus.WAITING) {
                // Skip items that are already reserved or in progress
                continue;
            }

            UUID sku = orderItem.getProduct().getSku();
            Integer requiredQuantity = orderItem.getQuantity();

            Stock match = findEligibleStock(sku, requiredQuantity).orElse(null);

            availableItems.add(new AvailableOrderItemDto(
                    orderItem.getId(),
                    orderItem.getProduct().getArticleNo(),
                    requiredQuantity,
                    match != null ? match.getAvailable() : 0,
                    match != null,
                    match != null ? match.getStorehouse().getId() : null,
                    orderItem.getFulfillmentStatus()
            ));
        }

        return availableItems;
    }

    /**
     * One query per order line instead of a scan over every storehouse: the database picks the
     * oldest stock row that holds enough unreserved units. Returning the {@link Stock} itself also
     * yields the available qty, so no second lookup is needed.
     */
    private Optional<Stock> findEligibleStock(UUID sku, Integer requiredQuantity) {
        return stockRepository.findEligibleBySku(sku, requiredQuantity).stream().findFirst();
    }
}
