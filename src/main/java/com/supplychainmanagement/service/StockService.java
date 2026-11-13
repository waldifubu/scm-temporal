package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.stock.InventoryRow;
import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.entity.Storehouse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What is on the shelves: booking, moving and finding stock. Implemented by {@code StockServiceImpl}. */
public interface StockService {

    /** What the warehouse holds per article, every storehouse added up. */
    Page<InventoryRow> findInventory(Pageable pageable);

    /**
     * Books {@code quantity} onto the stock of one SKU in one storehouse, creating the row the first time.
     * Joins the caller's transaction and reads the row {@code FOR UPDATE}.
     */
    Stock add(UUID sku, Long storehouseId, Integer quantity);

    List<Stock> findAllBySku(UUID sku);

    boolean isAvailable(UUID sku, int quantity, Long storehouseId);

    Stock transferItemToStock(UUID sku, Long storehouseFrom, Long storehouseTo, Integer quantity);

    Page<Stock> findAllByStorehouseId(Long id, Pageable pageable);

    /**
     * Where goods of this SKU should be booked in when nobody said: the storehouse that already holds the most of
     * it (a tie goes to the lower id), otherwise the one with the lowest id. Empty only when there is no storehouse.
     */
    Optional<Storehouse> suggestReceivingStorehouse(UUID sku);

    /**
     * Where a delivered <em>component</em> should be booked in when nobody said: the parts of one product have to lie in
     * <strong>one</strong> storehouse for {@code produce()} to build it, so this is the storehouse that already holds the
     * most <em>different</em> parts of the recipe ({@code recipeSkus}, with a positive {@code onHand}); a tie goes to
     * the one with more units of them, then to the lower id. Nothing of the recipe on stock anywhere: the answer of
     * {@link #suggestReceivingStorehouse(UUID)} for {@code sku}.
     */
    Optional<Storehouse> suggestReceivingStorehouseForPart(UUID sku, Collection<UUID> recipeSkus);
}
