package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.stock.AddStockRequest;
import com.supplychainmanagement.dto.stock.StockResponse;
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

    /**
     * Books a quantity onto the stock of one SKU in one storehouse, creating the row the first time
     * anything of that article lands there.
     * <p>
     * The body is {@link AddStockRequest} and used to be a {@code Map<String, String>}: unvalidated,
     * and a missing {@code sku} ended in an NPE inside the {@code "new"} check - a 500 for an
     * incomplete request. That {@code "new"} branch is gone with it. It made the controller invent a
     * random UUID and book stock onto a SKU no article carries and nothing can find again; a SKU
     * comes into being with the article it identifies, not with a booking.
     */
    @PostMapping(value = "/add", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'WAREHOUSE')")
    public ResponseEntity<StockResponse> addToStock(@Valid @RequestBody AddStockRequest request) {
        Stock stock = stockService.add(request.sku(), request.storehouseId(), request.qty());
        return ResponseEntity.ok().body(StockResponse.from(stock));
    }

    @PostMapping(value = "/transfer", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'WAREHOUSE')")
    public ResponseEntity<StockResponse> transfer(@Valid @RequestBody TransferStockRequest request) {
        Stock stock = stockService.transferItemToStock(
                request.sku(),
                request.storehouseFrom(),
                request.storehouseTo(),
                request.qty()
        );
        return ResponseEntity.ok().body(StockResponse.from(stock));
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
    public ResponseEntity<List<StockResponse>> stock(
            @PathVariable UUID sku,
            @RequestParam(defaultValue = "0") Long storehouseId
    ) {
        List<StockResponse> stockList = new ArrayList<>();

        for (Stock stock : stockService.findAllBySku(sku)) {
            if (stock.getStorehouse().getId().equals(storehouseId) || storehouseId == 0) {
                stockList.add(StockResponse.from(stock));
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
    public PageResponse<StockResponse> stockByStorehouse(
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

        // Mapped before the page leaves the method: the rows come out of the service's read-only
        // transaction and their storehouse is LAZY.
        return PageResponse.of(stockPage.map(StockResponse::from));
    }
}