package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.stock.TransferStockRequest;
import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.service.impl.StockService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping({"/api/{version}/stock"})
@AllArgsConstructor
public class StockController {

    private final StockService stockService;

    @PostMapping(value = "/add", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'WAREHOUSE')")
    public ResponseEntity<Stock> addToStock(
            @RequestBody Map<String, String> params
    ) {
        Integer quantity = requireNumericParam(params, "qty").intValue();
        Long storehouseId = requireNumericParam(params, "storehouseId");
        if (params.get("sku").equals("new")) {
            params.put("sku", UUID.randomUUID().toString());
        }
        UUID sku = UUID.fromString(params.get("sku"));

        Stock stock = stockService.add(sku, storehouseId, quantity);
        return ResponseEntity.ok().body(stock);
    }

    @PostMapping(value = "/transfer", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'WAREHOUSE')")
    public ResponseEntity<Stock> transfer(@Valid @RequestBody TransferStockRequest request) {
        Stock stock = stockService.transferStock(
                request.sku(),
                request.storehouseFrom(),
                request.storehouseTo(),
                request.qty()
        );
        return ResponseEntity.ok().body(stock);
    }

    private Long requireNumericParam(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Missing request parameter: " + name);
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Request parameter '" + name + "' must be numeric");
        }
    }

    /**
     * Returns a list of stock entries for the given SKU and optional storehouse ID.
     * If storehouseId is 0, it returns stock from all storehouses.
     *
     * @param sku
     * @param storehouseId
     * @return
     */
    @GetMapping(value = "/{sku}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'WAREHOUSE')")
    public ResponseEntity<List<Stock>> stock(
            @PathVariable UUID sku,
            @RequestParam(defaultValue = "0") Long storehouseId
    ) {
        List<Stock> stockList = new ArrayList<>();

        for (Stock stock : stockService.findAllBySku(sku)) {
            if (stock.getStorehouse().getId().equals(storehouseId) || storehouseId == 0) {
                stockList.add(stock);
            }
        }

        return ResponseEntity.ok().body(stockList);
    }
}