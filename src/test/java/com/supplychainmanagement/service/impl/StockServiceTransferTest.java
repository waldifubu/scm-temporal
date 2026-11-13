package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.repository.StockRepository;
import com.supplychainmanagement.repository.StorehouseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A transfer reads two stock rows and writes both back. Both are locked, <strong>the lower storehouse id first</strong>
 * whichever way the goods move: A to B and B to A at the same time would otherwise each hold one row and wait for the
 * other.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockServiceTransferTest {

    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");

    @Mock
    private StockRepository stockRepository;

    @InjectMocks
    private StockServiceImpl service;

    private Stock stock(long storehouseId, int onHand) {
        Storehouse storehouse = new Storehouse();
        storehouse.setId(storehouseId);
        Stock stock = new Stock();
        stock.setSku(SKU);
        stock.setStorehouse(storehouse);
        stock.setOnHand(onHand);
        stock.setReserved(0);
        when(stockRepository.findForUpdateByStorehouseIdAndSku(storehouseId, SKU)).thenReturn(Optional.of(stock));
        return stock;
    }

    @Test
    void movesTheQuantityFromTheSourceToTheTarget() {
        Stock source = stock(2L, 10);
        Stock target = stock(1L, 1);

        service.transferItemToStock(SKU, 2L, 1L, 4);

        assertThat(source.getOnHand()).isEqualTo(6);
        assertThat(target.getOnHand()).isEqualTo(5);
    }

    @Test
    void locksTheLowerStorehouseFirstInBothDirections() {
        stock(1L, 10);
        stock(2L, 10);

        service.transferItemToStock(SKU, 1L, 2L, 1);
        service.transferItemToStock(SKU, 2L, 1L, 1);

        InOrder order = inOrder(stockRepository);
        order.verify(stockRepository).findForUpdateByStorehouseIdAndSku(1L, SKU);
        order.verify(stockRepository).findForUpdateByStorehouseIdAndSku(2L, SKU);
        order.verify(stockRepository).findForUpdateByStorehouseIdAndSku(1L, SKU);
        order.verify(stockRepository).findForUpdateByStorehouseIdAndSku(2L, SKU);
    }

    @Test
    void readsNoRowWithoutALock() {
        stock(1L, 10);
        stock(2L, 10);

        service.transferItemToStock(SKU, 1L, 2L, 1);

        verify(stockRepository, never()).findByStorehouseIdAndSku(any(), any());
    }
}
