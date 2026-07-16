package com.supplychainmanagement.controller;

import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.service.impl.StockService;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/stock")
@AllArgsConstructor
public class StockController {

    private final StockService stockService;

    @PostMapping("/add")
    @PreAuthorize("hasRole('ROLE_ADMIN') or hasRole('ROLE_MANAGER') or hasRole('ROLE_CUSTOMER')")
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
}