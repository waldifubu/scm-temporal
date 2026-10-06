package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.repository.StorehouseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorehouseServiceImplTest {

    private final StorehouseRepository repository = mock(StorehouseRepository.class);
    private final StorehouseServiceImpl service = new StorehouseServiceImpl(repository);

    private static Storehouse storehouse(Long id, String name) {
        Storehouse storehouse = new Storehouse();
        storehouse.setId(id);
        storehouse.setName(name);
        storehouse.setAddress("Musterstr. 1");
        storehouse.setCity("Berlin");
        storehouse.setCountry("DE");
        return storehouse;
    }

    /** Every field a client names a storehouse by arrives, and the paging of the page is kept. */
    @Test
    void mapsStorehousesAndKeepsThePaging() {
        PageRequest pageable = PageRequest.of(2, 10);
        when(repository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(storehouse(7L, "Hauptlager")), pageable, 31));

        var page = service.findStorehouses(pageable);

        assertThat(page.getTotalElements()).isEqualTo(31);
        assertThat(page.getContent()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(7L);
            assertThat(row.name()).isEqualTo("Hauptlager");
            assertThat(row.address()).isEqualTo("Musterstr. 1");
            assertThat(row.city()).isEqualTo("Berlin");
            assertThat(row.country()).isEqualTo("DE");
        });
        verify(repository).findAll(pageable);
    }

    /**
     * The stocks collection is never touched: it is LAZY, so reading it would cost a query per
     * storehouse - and the response does not carry it. An entity that throws on access makes that a
     * fact instead of a comment.
     */
    @Test
    void neverTouchesTheStocksOfAStorehouse() {
        Storehouse touchy = new Storehouse() {
            @Override
            public List<Stock> getStocks() {
                throw new AssertionError("stocks must not be read for a list row");
            }
        };
        touchy.setId(1L);
        touchy.setName("Hauptlager");
        PageRequest pageable = PageRequest.of(0, 25);
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(touchy), pageable, 1));

        assertThat(service.findStorehouses(pageable).getContent()).hasSize(1);
    }
}
