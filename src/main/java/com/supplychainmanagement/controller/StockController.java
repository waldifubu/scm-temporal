package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.stock.TransferStockRequest;
import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.service.impl.StockService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
        Stock stock = stockService.transferItemToStock(
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
     * @param sku          the SKU to look up
     * @param storehouseId the storehouse ID to filter by (0 for all storehouses)
     * @return a ResponseEntity containing the list of Stock entries
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

        if (stockList.isEmpty()) {
            throw new ResourceNotFoundException("Stock", "sku " + sku + " in storehouse ", storehouseId);
        }

        return ResponseEntity.ok().body(stockList);
    }

    /**
     * Paged like the order list in {@code OrderController.list}, down to the parameter names.
     * Sorted by {@code sku} by default, so the same article keeps the same place across calls.
     */
    @GetMapping(value = "/storehouse/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'WAREHOUSE')")
    public PageResponse<Stock> stockByStorehouse(
            @PathVariable(required = false) Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "sku") String sort,
            @RequestParam(defaultValue = "ASC") String order
    ) {
        if (id == null) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Missing request parameter: storehouseId");
        }

        Sort.Direction dir = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sort));

        Page<Stock> stockPage = stockService.findAllByStorehouseId(id, pageable);
        // Measured against the total, not against this page: a page past the end is empty without
        // the storehouse being unknown, and only the latter is a 404.
        if (stockPage.getTotalElements() == 0) {
            throw new ResourceNotFoundException("Stock", "storehouse ID", id);
        }

        return PageResponse.of(stockPage);
    }
}