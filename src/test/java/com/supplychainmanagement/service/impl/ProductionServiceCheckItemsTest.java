package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.StockRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionServiceCheckItemsTest {

    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");

    @Mock
    private StockRepository stockRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private StockService stockService;

    @InjectMocks
    private ProductionServiceImpl service;

    private Storehouse storehouse(Long id) {
        Storehouse storehouse = new Storehouse();
        storehouse.setId(id);
        return storehouse;
    }

    private Stock stock(Long storehouseId, int onHand, int reserved) {
        Stock stock = new Stock();
        stock.setSku(SKU);
        stock.setOnHand(onHand);
        stock.setReserved(reserved);
        stock.setStorehouse(storehouse(storehouseId));
        return stock;
    }

    private Order orderWithOneItem(int quantity) {
        Product product = new Product();
        product.setArticleNo(1001L);
        product.setSku(SKU);

        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(quantity);

        Order order = new Order();
        order.setOrderItems(Set.of(item));
        return order;
    }

    @Test
    void reportsTheOldestStorehouseThatCanCoverTheLine() {
        when(stockRepository.findEligibleBySku(SKU, 3))
                .thenReturn(List.of(stock(7L, 10, 2), stock(9L, 50, 0)));

        var result = service.checkItems(orderWithOneItem(3));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().available()).isTrue();
        assertThat(result.getFirst().storehouseId()).isEqualTo(7L);
        assertThat(result.getFirst().availableQuantity()).isEqualTo(8); // onHand 10 - reserved 2
        assertThat(result.getFirst().orderQuantity()).isEqualTo(3);
    }

    @Test
    void reportsUnavailableWhenNoSingleStorehouseHasEnough() {
        when(stockRepository.findEligibleBySku(SKU, 5)).thenReturn(List.of());

        var result = service.checkItems(orderWithOneItem(5));

        assertThat(result.getFirst().available()).isFalse();
        assertThat(result.getFirst().storehouseId()).isNull();
        assertThat(result.getFirst().availableQuantity()).isZero();
    }

    /** A method called "check" must not write. */
    @Test
    void checkItemsWritesNothing() {
        when(stockRepository.findEligibleBySku(any(), eq(3))).thenReturn(List.of(stock(7L, 10, 2)));

        service.checkItems(orderWithOneItem(3));

        // The write paths of this service run through stockService.add and stockRepository.save -
        // a read-only availability check may touch neither.
        verify(stockRepository).findEligibleBySku(any(), eq(3));
        verifyNoMoreInteractions(stockRepository);
        verifyNoInteractions(stockService, productRepository);
    }

    /** And the second half: one query per order line, not one per line and storehouse. */
    @Test
    void queriesTheDatabaseExactlyOncePerOrderLine() {
        when(stockRepository.findEligibleBySku(any(), eq(3))).thenReturn(List.of());

        service.checkItems(orderWithOneItem(3));

        verify(stockRepository, times(1)).findEligibleBySku(SKU, 3);
        verify(stockRepository, times(1)).findEligibleBySku(any(), eq(3));
    }
}
