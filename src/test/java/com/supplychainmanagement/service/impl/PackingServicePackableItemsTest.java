package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.PackableItemDto;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.PackageItemRepository;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the service decides about the packable list: which statuses count, and that paging cannot
 * repeat or skip a line. The query itself runs in {@code CustomQueryExecutionTest} and, with data, in
 * {@code PackableItemsEndpointTest}.
 */
class PackingServicePackableItemsTest {

    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final PackingServiceImpl service = new PackingServiceImpl(orderItemRepository,
            mock(ShipmentPackageRepository.class), mock(PackageItemRepository.class));

    @SuppressWarnings("unchecked")
    private Pageable askedPageable(Pageable requested, Long orderNo) {
        when(orderItemRepository.findPackable(any(), any(), any())).thenAnswer(call ->
                new PageImpl<PackableItemDto>(List.of(), call.getArgument(2), 0));

        service.findPackableItems(orderNo, requested);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderItemRepository).findPackable(any(), eq(orderNo), captor.capture());
        return captor.getValue();
    }

    /** PICKED has nothing packed, PACKING some - the two with room left; nothing else may be offered. */
    @Test
    @SuppressWarnings("unchecked")
    void offersPickedAndPackingLinesOnly() {
        when(orderItemRepository.findPackable(any(), any(), any())).thenReturn(Page.empty());

        service.findPackableItems(null, PageRequest.of(0, 25));

        ArgumentCaptor<Collection<FulfillmentStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(orderItemRepository).findPackable(statuses.capture(), any(), any());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(FulfillmentStatus.PICKED, FulfillmentStatus.PACKING);
    }

    @Test
    void passesTheOrderNumberOn() {
        askedPageable(PageRequest.of(0, 25), 104321L);
        // verified inside askedPageable: eq(orderNo)
    }

    @Test
    void passesNullOnForEveryOrder() {
        askedPageable(PageRequest.of(0, 25), null);
    }

    /**
     * Lines picked together share their sort value, and the database may order the same rows
     * differently for page 2 than it did for page 1 - one line twice, another never. The id decides.
     */
    @Test
    void breaksTiesByIdSoPagingStaysStable() {
        Pageable asked = askedPageable(PageRequest.of(2, 10, Sort.by(Sort.Direction.DESC, "updatedAt")), null);

        assertThat(asked.getSort()).containsExactly(
                Sort.Order.desc("updatedAt"), Sort.Order.asc("id"));
    }

    @Test
    void doesNotAddTheIdTwiceWhenTheCallerSortsByIt() {
        Pageable asked = askedPageable(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "id")), null);

        assertThat(asked.getSort()).containsExactly(Sort.Order.desc("id"));
    }

    @Test
    void keepsThePageNumberAndSize() {
        Pageable asked = askedPageable(PageRequest.of(3, 7, Sort.by("quantity")), null);

        assertThat(asked.getPageNumber()).isEqualTo(3);
        assertThat(asked.getPageSize()).isEqualTo(7);
    }

    @Test
    void answersWhatTheRepositoryAnswers() {
        PackableItemDto row = new PackableItemDto(1L, 2L, 104321L, 9L, "Regal", 10, 4L,
                FulfillmentStatus.PACKING, 3L, "Lager Nord", LocalDateTime.of(2026, 10, 7, 9, 0));
        when(orderItemRepository.findPackable(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 25), 1));

        Page<PackableItemDto> page = service.findPackableItems(null, PageRequest.of(0, 25));

        assertThat(page.getContent()).containsExactly(row);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }
}
