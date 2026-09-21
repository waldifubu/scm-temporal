package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionServiceProduceTest {

    @Mock
    private StockRepository stockRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private StockService stockService;

    @InjectMocks
    private ProductionServiceImpl service;

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
