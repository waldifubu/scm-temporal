package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.shipping.PackageItemResponse;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.repository.PackageItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Siblings are the other package items of the same order line - computed, stored nowhere, and looked
 * up once for all items at hand.
 */
@ExtendWith(MockitoExtension.class)
class PackageItemResponseAssemblerTest {

    @Mock
    private PackageItemRepository packageItemRepository;

    @InjectMocks
    private PackageItemResponseAssembler assembler;

    private static OrderItem line(Long id) {
        Product product = new Product();
        product.setSku(UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8"));
        Order order = new Order();
        order.setOrderNo(1042L);

        OrderItem line = new OrderItem();
        line.setId(id);
        line.setQuantity(10);
        line.setProduct(product);
        line.setOrder(order);
        return line;
    }

    private static PackageItem item(Long id, OrderItem line) {
        PackageItem item = new PackageItem();
        item.setId(id);
        item.setOrderItem(line);
        item.setQuantity(5);
        return item;
    }

    private static PackageItemRepository.ItemOfLine idOf(Long orderItemId, Long packageItemId) {
        return new PackageItemRepository.ItemOfLine() {
            @Override
            public Long getOrderItemId() {
                return orderItemId;
            }

            @Override
            public Long getPackageItemId() {
                return packageItemId;
            }
        };
    }

    /**
     * Line 11 has three items - two of them in the response, one (105) elsewhere. Line 12 has only
     * its own. Each item lists the others of its line, never itself.
     */
    @Test
    void listsTheOtherItemsOfTheSameOrderLine() {
        OrderItem eleven = line(11L);
        OrderItem twelve = line(12L);
        when(packageItemRepository.findItemIdsByOrderItemIdIn(Set.of(11L, 12L))).thenReturn(List.of(
                idOf(11L, 101L), idOf(11L, 102L), idOf(12L, 103L), idOf(11L, 105L)));

        List<PackageItemResponse> responses = assembler.toResponses(List.of(
                item(101L, eleven), item(102L, eleven), item(103L, twelve)));

        assertThat(responses).extracting(PackageItemResponse::siblings).containsExactly(
                List.of(102L, 105L),
                List.of(101L, 105L),
                List.of());
    }

    /** One lookup for all items at hand - not one per item. */
    @Test
    void looksTheSiblingsUpOnceForAllItems() {
        OrderItem eleven = line(11L);
        when(packageItemRepository.findItemIdsByOrderItemIdIn(anyCollection())).thenReturn(List.of());

        assembler.toResponses(List.of(item(101L, eleven), item(102L, eleven), item(103L, line(12L))));

        verify(packageItemRepository, times(1)).findItemIdsByOrderItemIdIn(anyCollection());
    }

    @Test
    void asksNothingForNoItems() {
        assertThat(assembler.toResponses(List.<PackageItem>of())).isEmpty();

        verify(packageItemRepository, never()).findItemIdsByOrderItemIdIn(anyCollection());
    }
}
