package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.repository.StockRepository;
import com.supplychainmanagement.repository.StorehouseRepository;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@AllArgsConstructor
public class StockService {

    private final StockRepository stockRepository;
    private final StorehouseRepository storehouseRepository;

        public Stock add(UUID sku, Long storehouseId, Integer quantity) {
        Stock stock = stockRepository.findByStorehouseIdAndSku(storehouseId, sku).orElse(null);

        if(stock == null) {
            var storehouse = storehouseRepository.findById(String.valueOf(storehouseId)).orElseThrow(() -> new IllegalArgumentException("Storehouse not found"));
            stock = new Stock();
            stock.setSku(sku);
            stock.setStorehouse(storehouse);
            stock.setOnHand(0);
        }

        stock.setOnHand(stock.getOnHand() + quantity);
        stockRepository.save(stock);

        return stock;
    }
}
