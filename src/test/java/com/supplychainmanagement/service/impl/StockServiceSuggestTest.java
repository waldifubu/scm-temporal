package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.repository.StockRepository;
import com.supplychainmanagement.repository.StorehouseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** {@code suggestReceivingStorehouse}: where goods are booked in when nobody said. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockServiceSuggestTest {

    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");

    @Mock
    private StockRepository stockRepository;
    @Mock
    private StorehouseRepository storehouseRepository;

    @InjectMocks
    private StockServiceImpl service;

    private static Storehouse storehouse(long id) {
        Storehouse storehouse = new Storehouse();
        storehouse.setId(id);
        return storehouse;
    }

    private static Stock stock(Storehouse storehouse, int onHand) {
        Stock stock = new Stock();
        stock.setSku(SKU);
        stock.setStorehouse(storehouse);
        stock.setOnHand(onHand);
        stock.setReserved(0);
        return stock;
    }

    @Test
    void prefersTheStorehouseHoldingTheMost() {
        when(stockRepository.findBySku(SKU)).thenReturn(List.of(
                stock(storehouse(1), 5), stock(storehouse(3), 40), stock(storehouse(2), 12)));

        assertThat(service.suggestReceivingStorehouse(SKU)).hasValueSatisfying(s -> assertThat(s.getId()).isEqualTo(3L));
    }

    @Test
    void aTieGoesToTheLowerId() {
        when(stockRepository.findBySku(SKU)).thenReturn(List.of(
                stock(storehouse(9), 10), stock(storehouse(4), 10), stock(storehouse(6), 10)));

        assertThat(service.suggestReceivingStorehouse(SKU)).hasValueSatisfying(s -> assertThat(s.getId()).isEqualTo(4L));
    }

    @Test
    void anArticleNobodyHoldsGoesToTheFirstStorehouse() {
        when(stockRepository.findBySku(SKU)).thenReturn(List.of());
        when(storehouseRepository.findAll()).thenReturn(List.of(storehouse(8), storehouse(2), storehouse(5)));

        assertThat(service.suggestReceivingStorehouse(SKU)).hasValueSatisfying(s -> assertThat(s.getId()).isEqualTo(2L));
    }

    @Test
    void isEmptyWhenThereIsNoStorehouseAtAll() {
        when(stockRepository.findBySku(SKU)).thenReturn(List.of());
        when(storehouseRepository.findAll()).thenReturn(List.of());

        assertThat(service.suggestReceivingStorehouse(SKU)).isEmpty();
    }

    // ---- suggestReceivingStorehouseForPart: the parts of one product belong in one storehouse

    private static final UUID SCREW = UUID.fromString("806a99c3-944b-11f1-9b51-001e064520d8");
    private static final UUID PLANK = UUID.fromString("906a99c3-944b-11f1-9b51-001e064520d8");
    private static final UUID HINGE = UUID.fromString("a06a99c3-944b-11f1-9b51-001e064520d8");

    private Stock held(UUID sku, Storehouse storehouse, int onHand) {
        Stock stock = stock(storehouse, onHand);
        stock.setSku(sku);
        return stock;
    }

    /** The storehouse with the most different recipe parts wins, however much of one part another one holds. */
    @Test
    void aPartGoesToTheStorehouseHoldingTheMostDifferentPartsOfItsRecipe() {
        Storehouse one = storehouse(1);
        Storehouse two = storehouse(2);
        when(stockRepository.findBySku(SCREW)).thenReturn(List.of(held(SCREW, one, 5000)));
        when(stockRepository.findBySku(PLANK)).thenReturn(List.of(held(PLANK, two, 10)));
        when(stockRepository.findBySku(HINGE)).thenReturn(List.of(held(HINGE, two, 10)));

        assertThat(service.suggestReceivingStorehouseForPart(SCREW, List.of(SCREW, PLANK, HINGE)))
                .hasValueSatisfying(s -> assertThat(s.getId()).isEqualTo(2L));
    }

    /** Same number of parts: more units, then the lower id. */
    @Test
    void aTieOnPartsGoesToTheMoreUnitsThenTheLowerId() {
        Storehouse one = storehouse(1);
        Storehouse two = storehouse(2);
        when(stockRepository.findBySku(PLANK)).thenReturn(List.of(held(PLANK, one, 10), held(PLANK, two, 30)));
        assertThat(service.suggestReceivingStorehouseForPart(SCREW, List.of(PLANK)))
                .hasValueSatisfying(s -> assertThat(s.getId()).isEqualTo(2L));

        when(stockRepository.findBySku(PLANK)).thenReturn(List.of(held(PLANK, two, 10), held(PLANK, one, 10)));
        assertThat(service.suggestReceivingStorehouseForPart(SCREW, List.of(PLANK)))
                .hasValueSatisfying(s -> assertThat(s.getId()).isEqualTo(1L));
    }

    /** A stock row with nothing on hand is not "kept there". */
    @Test
    void emptyStockRowsDoNotCount() {
        Storehouse one = storehouse(1);
        Storehouse two = storehouse(2);
        when(stockRepository.findBySku(PLANK)).thenReturn(List.of(held(PLANK, one, 0), held(PLANK, two, 3)));

        assertThat(service.suggestReceivingStorehouseForPart(SCREW, List.of(PLANK)))
                .hasValueSatisfying(s -> assertThat(s.getId()).isEqualTo(2L));
    }

    /** Nothing of the recipe on stock anywhere: the answer for the part alone, then the first storehouse. */
    @Test
    void fallsBackToThePartAloneAndThenToTheFirstStorehouse() {
        when(stockRepository.findBySku(PLANK)).thenReturn(List.of());
        when(stockRepository.findBySku(SCREW)).thenReturn(List.of(held(SCREW, storehouse(7), 4)));
        assertThat(service.suggestReceivingStorehouseForPart(SCREW, List.of(PLANK)))
                .hasValueSatisfying(s -> assertThat(s.getId()).isEqualTo(7L));

        when(stockRepository.findBySku(SCREW)).thenReturn(List.of());
        when(storehouseRepository.findAll()).thenReturn(List.of(storehouse(8), storehouse(3)));
        assertThat(service.suggestReceivingStorehouseForPart(SCREW, java.util.Arrays.asList(PLANK, null)))
                .hasValueSatisfying(s -> assertThat(s.getId()).isEqualTo(3L));
    }
}
