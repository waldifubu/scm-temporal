package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Component;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionServiceProduceTest {

    @Mock
    private StockRepository stockRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private StockService stockService;

    @InjectMocks
    private ProductionServiceImpl service;

    private static final UUID SCREW = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");
    private static final UUID PLANK = UUID.fromString("806a99c3-944b-11f1-9b51-001e064520d8");
    private static final Long STOREHOUSE_ID = 7L;

    /** A product built from the given components. */
    private static Product productOf(Component... components) {
        Product product = new Product();
        product.setName("shelf");
        product.setSku(UUID.fromString("906a99c3-944b-11f1-9b51-001e064520d8"));
        product.setComponents(new ArrayList<>(List.of(components)));
        for (Component component : components) {
            component.setProduct(product);
        }
        return product;
    }

    private static Component component(UUID sku, Integer qty) {
        Component component = new Component();
        component.setSku(sku);
        component.setQty(qty);
        return component;
    }

    /** Stock of one component in the one storehouse the tests use. */
    private Stock stocked(UUID sku, int available) {
        Storehouse storehouse = new Storehouse();
        storehouse.setId(STOREHOUSE_ID);

        Stock stock = new Stock();
        stock.setSku(sku);
        stock.setStorehouse(storehouse);
        stock.setOnHand(available);
        stock.setReserved(0);

        when(stockRepository.findAvailableBySkuOrderByUpdatedAtAsc(sku)).thenReturn(List.of(stock));
        when(stockRepository.findByStorehouseIdAndSku(STOREHOUSE_ID, sku)).thenReturn(Optional.of(stock));
        return stock;
    }

    private void producing(Product product) {
        PageRequest pageable = PageRequest.of(0, 10);
        when(productRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(product), pageable, 1));
        service.produce(pageable);
    }

    /**
     * The recipe quantity is what gets consumed. Every line used to count as one, so a product
     * needing four screws took one off the shelf and was produced out of stock that was never there.
     */
    @Test
    void consumesTheQuantityTheRecipeAsksFor() {
        Stock screws = stocked(SCREW, 10);

        producing(productOf(component(SCREW, 4)));

        assertThat(screws.getOnHand()).isEqualTo(6);
        verify(stockService).add(any(UUID.class), eq(STOREHOUSE_ID), eq(1));
    }

    /** Enough rows, not enough pieces: eight screws do not cover a recipe asking for ten. */
    @Test
    void doesNotProduceWhenTheQuantityIsNotCovered() {
        Stock screws = stocked(SCREW, 8);

        producing(productOf(component(SCREW, 10)));

        assertThat(screws.getOnHand()).isEqualTo(8);
        verifyNoInteractions(stockService);
    }

    /** Each line with its own quantity. */
    @Test
    void consumesEachComponentWithItsOwnQuantity() {
        Stock screws = stocked(SCREW, 20);
        Stock planks = stocked(PLANK, 20);

        producing(productOf(component(SCREW, 8), component(PLANK, 3)));

        assertThat(screws.getOnHand()).isEqualTo(12);
        assertThat(planks.getOnHand()).isEqualTo(17);
    }

    /**
     * A quantity that cannot be meant is refused rather than guessed at - produce() runs unattended
     * every 150 s, and a wrong recipe consumes real stock.
     */
    @Test
    void refusesARecipeLineWithoutAUsableQuantity() {
        Stock screws = stocked(SCREW, 10);

        producing(productOf(component(SCREW, 0)));

        assertThat(screws.getOnHand()).isEqualTo(10);
        verifyNoInteractions(stockService);
    }

    /**
     * The page reports the total of the products paged over, not how many were produced on this
     * page. Here nothing can be produced - no components - and the total still reads 7, so a client
     * knows there are further pages to work through.
     */
    @Test
    void reportsTheTotalOfTheProductsNotOfTheProduced() {
        PageRequest pageable = PageRequest.of(0, 2);
        Product first = new Product();
        first.setName("first");
        Product second = new Product();
        second.setName("second");
        when(productRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(first, second), pageable, 7));

        var page = service.produce(pageable);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(7);
        assertThat(page.getTotalPages()).isEqualTo(4);
    }
}
