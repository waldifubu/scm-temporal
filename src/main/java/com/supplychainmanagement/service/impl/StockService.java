package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.repository.StockRepository;
import com.supplychainmanagement.repository.StorehouseRepository;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * Books {@code quantity} onto the stock of one SKU in one storehouse, creating the row if this is
     * the first time anything of it lands there.
     * <p>
     * {@code @Transactional} and read {@code FOR UPDATE}, like everywhere else in this codebase where
     * a value is read, checked and written back. It was neither: called from
     * {@code POST /stock/add} there was no transaction at all, so the read and the write were two of
     * them, and two concurrent bookings on the same row both computed from the same quantity. What
     * saved the data was {@code @Version} - and what the caller saw was a 500 from the
     * {@code Exception} handler for something they had done nothing wrong in. The goods receipt and
     * {@code produce()} book into the same rows, and {@code assemble()} runs every 150 s.
     * <p>
     * Joining the caller's transaction is deliberate rather than {@code REQUIRES_NEW}: the goods
     * receipt is all or nothing - if the booking fails, the request status must not move either.
     * <p>
     * One race is left and it cannot be closed here: if the row does not exist yet, there is nothing
     * to lock, so two first-ever bookings of the same SKU and storehouse can both insert and the
     * second hits {@code uq_stock_storehouse_sku}. Retrying would need a transaction of its own,
     * which is exactly what the caller must not have. It is flushed here instead of at the outer
     * commit, so it surfaces as a 409 saying what happened rather than as a 500 from somewhere else.
     */
    @Transactional
    public Stock add(UUID sku, Long storehouseId, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Quantity must be greater than zero");
        }

        Stock stock = stockRepository.findForUpdateByStorehouseIdAndSku(storehouseId, sku).orElse(null);

        if (stock == null) {
            var storehouse = storehouseRepository.findById(storehouseId)
                    .orElseThrow(() -> new IllegalArgumentException("Storehouse not found"));
            stock = new Stock();
            stock.setSku(sku);
            stock.setStorehouse(storehouse);
            stock.setOnHand(0);
            stock.setReserved(0);
            stock.setOnHand(quantity);
            try {
                return stockRepository.saveAndFlush(stock);
            } catch (DataIntegrityViolationException e) {
                // The only thing uq_stock_storehouse_sku can mean here: somebody else created the
                // row between the lock attempt finding nothing and this insert.
                throw new APIException(HttpStatus.CONFLICT, "Stock for " + sku + " in storehouse "
                        + storehouseId + " was created concurrently - repeat the booking");
            }
        }

        stock.setOnHand(stock.getOnHand() + quantity);
        return stockRepository.save(stock);
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
    public Stock transferItemToStock(UUID sku, Long storehouseFrom, Long storehouseTo, Integer quantity) {
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
            throw new APIException(HttpStatus.BAD_REQUEST, "Not enough available qty in source stock");
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

    public Page<Stock> findAllByStorehouseId(Long id, Pageable pageable) {
        return stockRepository.findByStorehouseId(id, pageable);
    }

    private List<Storehouse> getAllStorehouses() {
        return storehouseRepository.findAll();
    }

    private Storehouse getAvailableStorehouse(UUID sku, Integer requiredQuantity) {
        for (Storehouse storehouse : getAllStorehouses()) {
            if (isAvailable(sku, requiredQuantity, storehouse.getId())) {
                return storehouse;
            }
        }
        return null;
    }
}
