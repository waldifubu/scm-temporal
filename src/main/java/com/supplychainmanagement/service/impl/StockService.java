package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.repository.StockRepository;
import com.supplychainmanagement.repository.StorehouseRepository;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@AllArgsConstructor
public class StockService {

    private final StockRepository stockRepository;
    private final StorehouseRepository storehouseRepository;

    public Stock add(UUID sku, Long storehouseId, Integer quantity) {
        Stock stock = stockRepository.findByStorehouseIdAndSku(storehouseId, sku).orElse(null);

        if (stock == null) {
            var storehouse = storehouseRepository.findById(storehouseId)
                    .orElseThrow(() -> new IllegalArgumentException("Storehouse not found"));
            stock = new Stock();
            stock.setSku(sku);
            stock.setStorehouse(storehouse);
            stock.setOnHand(0);
            stock.setReserved(0);
        }

        stock.setOnHand(stock.getOnHand() + quantity);
        stockRepository.save(stock);

        return stock;
    }

    public List<Stock> findAllBySku(UUID sku) {
        return stockRepository.findBySku(sku);
    }

    public Stock findStock(UUID sku, Long storehouseId) {
        return stockRepository.findByStorehouseIdAndSku(storehouseId, sku)
                .orElseThrow(() -> new APIException(HttpStatus.NOT_FOUND, "Stock not found"));
    }

    public boolean isAvailable(UUID sku, int quantity, Long storehouseId) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }

        return stockRepository.findByStorehouseIdAndSku(storehouseId, sku)
                .map(stock -> stock.getAvailable() >= quantity)
                .orElse(false);
    }

    public int getAvailableQuantity(UUID sku, Long storehouseId) {
        return stockRepository.findByStorehouseIdAndSku(storehouseId, sku)
                .map(Stock::getAvailable)
                .orElse(0);
    }

    @Transactional
    public Stock transferStock(UUID sku, Long storehouseFrom, Long storehouseTo, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Quantity must be greater than zero");
        }

        if (storehouseFrom == null || storehouseTo == null) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Both storehouses are required");
        }

        if (storehouseFrom.equals(storehouseTo)) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Source and target storehouse must be different");
        }

        Stock sourceStock = stockRepository.findByStorehouseIdAndSku(storehouseFrom, sku)
                .orElseThrow(() -> new APIException(HttpStatus.NOT_FOUND, "Source stock not found"));

        if (sourceStock.getAvailable() < quantity) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Not enough available quantity in source stock");
        }

        Stock targetStock = stockRepository.findByStorehouseIdAndSku(storehouseTo, sku)
                .orElseGet(() -> {
                    var targetStorehouse = storehouseRepository.findById(storehouseTo)
                            .orElseThrow(() -> new APIException(HttpStatus.NOT_FOUND, "Target storehouse not found"));

                    Stock newStock = new Stock();
                    newStock.setSku(sku);
                    newStock.setStorehouse(targetStorehouse);
                    newStock.setOnHand(0);
                    newStock.setReserved(0);
                    return newStock;
                });

        sourceStock.setOnHand(sourceStock.getOnHand() - quantity);
        targetStock.setOnHand(targetStock.getOnHand() + quantity);

        stockRepository.save(sourceStock);
        stockRepository.save(targetStock);

        return targetStock;
    }
}
