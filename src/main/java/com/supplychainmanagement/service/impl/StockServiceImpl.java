package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.stock.InventoryRow;
import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.repository.StockInventoryRepository;
import com.supplychainmanagement.repository.StockRepository;
import com.supplychainmanagement.repository.StorehouseRepository;
import com.supplychainmanagement.service.StockService;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@AllArgsConstructor
public class StockServiceImpl implements StockService {

    private final StockRepository stockRepository;
    private final StorehouseRepository storehouseRepository;
    private final StockInventoryRepository stockInventoryRepository;

    /** What the warehouse holds per article, every storehouse added up. */
    @Transactional(readOnly = true)
    @Override
    public Page<InventoryRow> findInventory(Pageable pageable) {
        return stockInventoryRepository.findInventory(pageable);
    }

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
    @Override
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

    @Override
    public List<Stock> findAllBySku(UUID sku) {
        return stockRepository.findBySku(sku);
    }

    @Override
    public boolean isAvailable(UUID sku, int quantity, Long storehouseId) {

        return stockRepository.findByStorehouseIdAndSku(storehouseId, sku)
                .map(stock -> stock.getAvailable() >= quantity)
                .orElse(false);
    }

    @Transactional
    @Override
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

        // Both rows are locked, lower storehouse id first: A to B and B to A at the same time would otherwise each hold
        // one row and wait for the other. The target may not exist yet - then there is nothing to lock.
        Long lower = Math.min(storehouseFrom, storehouseTo);
        Long higher = Math.max(storehouseFrom, storehouseTo);
        Stock lowerStock = stockRepository.findForUpdateByStorehouseIdAndSku(lower, sku).orElse(null);
        Stock higherStock = stockRepository.findForUpdateByStorehouseIdAndSku(higher, sku).orElse(null);
        Stock existingSource = storehouseFrom.equals(lower) ? lowerStock : higherStock;
        Stock existingTarget = storehouseTo.equals(lower) ? lowerStock : higherStock;

        Stock sourceStock = java.util.Optional.ofNullable(existingSource)
                .orElseThrow(() -> new APIException(HttpStatus.NOT_FOUND, "Source stock not found"));

        if (sourceStock.getAvailable() < quantity) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Not enough available qty in source stock");
        }

        Stock targetStock = java.util.Optional.ofNullable(existingTarget)
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

    @Override
    public Page<Stock> findAllByStorehouseId(Long id, Pageable pageable) {
        return stockRepository.findByStorehouseId(id, pageable);
    }

    /**
     * Where goods of this SKU should be booked in when nobody said: the storehouse that already holds the most of it
     * (a tie goes to the lower id), otherwise the one with the lowest id. Empty only when there is no storehouse at all.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Storehouse> suggestReceivingStorehouse(UUID sku) {
        return stockRepository.findBySku(sku).stream()
                .max(Comparator.comparingInt(Stock::getOnHand)
                        .thenComparing(stock -> stock.getStorehouse().getId(), Comparator.reverseOrder()))
                .map(Stock::getStorehouse)
                .or(() -> storehouseRepository.findAll().stream().min(Comparator.comparing(Storehouse::getId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Storehouse> suggestReceivingStorehouseForPart(UUID sku, Collection<UUID> recipeSkus) {
        // per storehouse: which recipe parts it holds (distinct) and how many units of them
        Map<Long, Set<UUID>> partsHeld = new HashMap<>();
        Map<Long, Integer> unitsHeld = new HashMap<>();
        Map<Long, Storehouse> storehouses = new HashMap<>();
        for (UUID recipeSku : new LinkedHashSet<>(recipeSkus)) {
            if (recipeSku == null) {
                continue;
            }
            for (Stock stock : stockRepository.findBySku(recipeSku)) {
                if (stock.getOnHand() <= 0) {
                    continue;
                }
                Long id = stock.getStorehouse().getId();
                storehouses.putIfAbsent(id, stock.getStorehouse());
                partsHeld.computeIfAbsent(id, key -> new HashSet<>()).add(recipeSku);
                unitsHeld.merge(id, stock.getOnHand(), Integer::sum);
            }
        }
        return storehouses.keySet().stream()
                .max(Comparator.<Long>comparingInt(id -> partsHeld.get(id).size())
                        .thenComparingInt(unitsHeld::get)
                        .thenComparing(Comparator.reverseOrder()))
                .map(storehouses::get)
                .or(() -> suggestReceivingStorehouse(sku));
    }
}
