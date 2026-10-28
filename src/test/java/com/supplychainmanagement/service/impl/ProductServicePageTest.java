package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.repository.ProductCategoryRepository;
import com.supplychainmanagement.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The page of products: the id as the tie-break behind the caller's sort, and the components of a page
 * fetched in one more query - not with the page itself, which would page in memory.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServicePageTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductCategoryRepository productCategoryRepository;

    @InjectMocks
    private ProductServiceImpl service;

    private static Product product(long id) {
        Product product = new Product();
        product.setId(id);
        return product;
    }

    private static final Pageable BY_NAME = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "name"));
    private static final Pageable BY_NAME_THEN_ID = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "name").and(Sort.by("id")));

    @Test
    void addsTheIdAsTheTieBreakBehindTheCallersSort() {
        when(productRepository.findAllBy(any(Pageable.class))).thenReturn(Page.empty());

        service.findPage(BY_NAME, false);

        verify(productRepository).findAllBy(BY_NAME_THEN_ID);
    }

    @Test
    void doesNotRepeatTheIdWhenTheCallerSortsByIt() {
        Pageable byId = PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "id"));
        when(productRepository.findAllBy(any(Pageable.class))).thenReturn(Page.empty());

        service.findPage(byId, false);

        verify(productRepository).findAllBy(byId);
    }

    @Test
    void asksForNoComponentsWhenTheCallerGetsNone() {
        when(productRepository.findAllBy(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product(1), product(2)), BY_NAME_THEN_ID, 9));

        Page<Product> page = service.findPage(BY_NAME, false);

        assertThat(page.getContent()).extracting(Product::getId).containsExactly(1L, 2L);
        verify(productRepository, never()).findWithComponentsByIdIn(any());
    }

    @Test
    void fetchesTheComponentsOfThePageInOneQueryAndKeepsThePagesOrderAndFigures() {
        when(productRepository.findAllBy(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product(5), product(3)), BY_NAME_THEN_ID, 9));
        // Answered in another order than the page has, as a database may.
        Product three = product(3);
        Product five = product(5);
        when(productRepository.findWithComponentsByIdIn(any())).thenReturn(List.of(three, five));

        Page<Product> page = service.findPage(BY_NAME, true);

        assertThat(page.getContent()).containsExactly(five, three);
        assertThat(page.getTotalElements()).isEqualTo(9);
        assertThat(page.getNumber()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(2);
        verify(productRepository).findWithComponentsByIdIn(List.of(5L, 3L));
    }

    @Test
    void asksNothingMoreForAnEmptyPage() {
        when(productRepository.findAllBy(any(Pageable.class))).thenReturn(Page.empty());

        service.findPage(BY_NAME, true);

        verify(productRepository, never()).findWithComponentsByIdIn(any());
    }
}
