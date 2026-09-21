package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.PackageItemResponse;
import com.supplychainmanagement.dto.shipping.ShipmentPackageListDto;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.repository.PackageItemRepository;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import com.supplychainmanagement.service.PackageItemResponseAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShippingServiceImplTest {

    private static final Pageable SECOND_PAGE = PageRequest.of(1, 2);

    @Mock
    private ShipmentPackageRepository shipmentPackageRepository;
    @Mock
    private PackageItemRepository packageItemRepository;

    private ShippingServiceImpl service;

    /** A real assembler over the mocked repository, so the responses are built as in production. */
    @BeforeEach
    void setUp() {
        service = new ShippingServiceImpl(shipmentPackageRepository, packageItemRepository,
                new PackageItemResponseAssembler(packageItemRepository));
    }

    private static ShipmentPackage shipmentPackage(Long id, ShipmentPackageStatus status) {
        ShipmentPackage shipmentPackage = new ShipmentPackage();
        shipmentPackage.setId(id);
        shipmentPackage.setShipmentPackageStatus(status);
        return shipmentPackage;
    }

    /**
     * Status and package number are what the list is filtered by - the old endpoint accepted the
     * status and listed everything.
     */
    @Test
    void filtersByStatusAndPackageNumber() {
        when(shipmentPackageRepository.findAllByShipmentPackageStatusAndPackageNumberContaining(
                ShipmentPackageStatus.PACKED, "PKG-2026", SECOND_PAGE))
                .thenReturn(Page.empty(SECOND_PAGE));

        service.findShipmentPackages(ShipmentPackageStatus.PACKED, "PKG-2026", SECOND_PAGE);

        verify(shipmentPackageRepository).findAllByShipmentPackageStatusAndPackageNumberContaining(
                ShipmentPackageStatus.PACKED, "PKG-2026", SECOND_PAGE);
        verify(shipmentPackageRepository, never()).findAll(any(Pageable.class));
    }

    /**
     * The page decides order and totals; the second query only supplies the contents, in whatever
     * order the database returns them.
     */
    @Test
    void keepsThePageOrderAndTotalsWhileLoadingTheContentsSeparately() {
        ShipmentPackage second = shipmentPackage(2L, ShipmentPackageStatus.OPEN);
        ShipmentPackage first = shipmentPackage(1L, ShipmentPackageStatus.OPEN);
        when(shipmentPackageRepository.findAllByShipmentPackageStatus(ShipmentPackageStatus.OPEN, SECOND_PAGE))
                .thenReturn(new PageImpl<>(List.of(second, first), SECOND_PAGE, 7));
        when(shipmentPackageRepository.findWithItemsByIdIn(List.of(2L, 1L))).thenReturn(List.of(first, second));

        Page<ShipmentPackageListDto> rows = service.findShipmentPackages(ShipmentPackageStatus.OPEN, "", SECOND_PAGE);

        assertThat(rows.getContent()).extracting(ShipmentPackageListDto::id).containsExactly(2L, 1L);
        assertThat(rows.getTotalElements()).isEqualTo(7);
        assertThat(rows.getNumber()).isEqualTo(1);
        verify(shipmentPackageRepository).findWithItemsByIdIn(List.of(2L, 1L));
    }

    /** Nothing on the page, nothing to load - and an empty IN would not be valid SQL anyway. */
    @Test
    void skipsTheContentsQueryForAnEmptyPage() {
        when(shipmentPackageRepository.findAllByShipmentPackageStatus(ShipmentPackageStatus.DISPATCHED, SECOND_PAGE))
                .thenReturn(Page.empty(SECOND_PAGE));

        assertThat(service.findShipmentPackages(ShipmentPackageStatus.DISPATCHED, "", SECOND_PAGE)).isEmpty();

        verify(shipmentPackageRepository, never()).findWithItemsByIdIn(anyCollection());
    }

    /** Loose items and items in a package alike; a loose one has no package id. */
    @Test
    void listsPackageItemsLooseOrInAPackage() {
        UUID sku = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");
        Product product = new Product();
        product.setSku(sku);
        Order order = new Order();
        order.setOrderNo(1042L);
        OrderItem line = new OrderItem();
        line.setId(11L);
        line.setQuantity(10);
        line.setProduct(product);
        line.setOrder(order);

        PackageItem loose = new PackageItem();
        loose.setId(101L);
        loose.setOrderItem(line);
        loose.setQuantity(5);

        PackageItem packed = new PackageItem();
        packed.setId(102L);
        packed.setOrderItem(line);
        packed.setQuantity(5);
        packed.setShipmentPackage(shipmentPackage(7L, ShipmentPackageStatus.OPEN));

        when(packageItemRepository.findAllWithProductBy(SECOND_PAGE))
                .thenReturn(new PageImpl<>(List.of(loose, packed), SECOND_PAGE, 4));

        Page<PackageItemResponse> rows = service.findPackageItems(SECOND_PAGE);

        assertThat(rows.getContent()).extracting(PackageItemResponse::id).containsExactly(101L, 102L);
        assertThat(rows.getContent()).extracting(PackageItemResponse::shipmentPackageId).containsExactly(null, 7L);
        assertThat(rows.getContent()).extracting(PackageItemResponse::sku).containsOnly(sku.toString());
        assertThat(rows.getTotalElements()).isEqualTo(4);
    }

    /** Loose items come from their own query - the filter is the database's, not a stream filter. */
    @Test
    void listsTheLooseItemsThroughTheirOwnQuery() {
        Product product = new Product();
        product.setSku(UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8"));
        Order order = new Order();
        order.setOrderNo(1042L);
        OrderItem line = new OrderItem();
        line.setId(11L);
        line.setQuantity(10);
        line.setProduct(product);
        line.setOrder(order);

        PackageItem loose = new PackageItem();
        loose.setId(101L);
        loose.setOrderItem(line);
        loose.setQuantity(5);

        when(packageItemRepository.findAllWithProductByShipmentPackageIsNull(SECOND_PAGE))
                .thenReturn(new PageImpl<>(List.of(loose), SECOND_PAGE, 3));

        Page<PackageItemResponse> rows = service.findLoosePackageItems(SECOND_PAGE);

        assertThat(rows.getContent()).extracting(PackageItemResponse::id).containsExactly(101L);
        assertThat(rows.getContent()).extracting(PackageItemResponse::shipmentPackageId).containsOnlyNulls();
        assertThat(rows.getTotalElements()).isEqualTo(3);
        verify(packageItemRepository, never()).findAllWithProductBy(any());
    }

    /**
     * No number, an empty one or only blanks: filtered by status alone. The LIKE variant would drop
     * every package whose number is NULL from a list that asked for no number at all.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void filtersByStatusAloneWithoutAPackageNumber(String packageNumber) {
        when(shipmentPackageRepository.findAllByShipmentPackageStatus(ShipmentPackageStatus.OPEN, SECOND_PAGE))
                .thenReturn(Page.empty(SECOND_PAGE));

        service.findShipmentPackages(ShipmentPackageStatus.OPEN, packageNumber, SECOND_PAGE);

        verify(shipmentPackageRepository).findAllByShipmentPackageStatus(ShipmentPackageStatus.OPEN, SECOND_PAGE);
        verify(shipmentPackageRepository, never())
                .findAllByShipmentPackageStatusAndPackageNumberContaining(any(), any(), any());
    }

    /** Blanks around a number typed into a search field are not part of it. */
    @Test
    void trimsThePackageNumber() {
        when(shipmentPackageRepository.findAllByShipmentPackageStatusAndPackageNumberContaining(
                ShipmentPackageStatus.OPEN, "PKG-2026", SECOND_PAGE))
                .thenReturn(Page.empty(SECOND_PAGE));

        service.findShipmentPackages(ShipmentPackageStatus.OPEN, "  PKG-2026 ", SECOND_PAGE);

        verify(shipmentPackageRepository).findAllByShipmentPackageStatusAndPackageNumberContaining(
                ShipmentPackageStatus.OPEN, "PKG-2026", SECOND_PAGE);
    }

    @Test
    void findsOnePackageItem() {
        Product product = new Product();
        product.setSku(UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8"));
        Order order = new Order();
        order.setOrderNo(1042L);
        OrderItem line = new OrderItem();
        line.setId(11L);
        line.setQuantity(10);
        line.setProduct(product);
        line.setOrder(order);

        PackageItem item = new PackageItem();
        item.setId(101L);
        item.setOrderItem(line);
        item.setQuantity(5);
        when(packageItemRepository.findWithProductById(101L)).thenReturn(java.util.Optional.of(item));

        PackageItemResponse found = service.findPackageItem(101L);

        assertThat(found.id()).isEqualTo(101L);
        assertThat(found.orderNo()).isEqualTo(1042L);
    }

    @Test
    void findsOneShipmentPackage() {
        when(shipmentPackageRepository.findWithItemsById(5L))
                .thenReturn(java.util.Optional.of(shipmentPackage(5L, ShipmentPackageStatus.OPEN)));

        assertThat(service.findShipmentPackage(5L).id()).isEqualTo(5L);
    }

    @Test
    void answersUnknownIdsWithNotFound() {
        when(packageItemRepository.findWithProductById(999L)).thenReturn(java.util.Optional.empty());
        when(shipmentPackageRepository.findWithItemsById(999L)).thenReturn(java.util.Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.findPackageItem(999L))
                .isInstanceOf(com.supplychainmanagement.exception.ResourceNotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.findShipmentPackage(999L))
                .isInstanceOf(com.supplychainmanagement.exception.ResourceNotFoundException.class);
    }

    /** The list carries each item's siblings - computed from one lookup over the page's order lines. */
    @Test
    void addsTheSiblingsOfEachItem() {
        Product product = new Product();
        product.setSku(UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8"));
        Order order = new Order();
        order.setOrderNo(1042L);
        OrderItem line = new OrderItem();
        line.setId(11L);
        line.setQuantity(10);
        line.setProduct(product);
        line.setOrder(order);

        PackageItem item = new PackageItem();
        item.setId(101L);
        item.setOrderItem(line);
        item.setQuantity(5);

        when(packageItemRepository.findAllWithProductBy(SECOND_PAGE))
                .thenReturn(new PageImpl<>(List.of(item), SECOND_PAGE, 3));
        when(packageItemRepository.findItemIdsByOrderItemIdIn(java.util.Set.of(11L)))
                .thenReturn(List.of(line(11L, 101L), line(11L, 104L)));

        assertThat(service.findPackageItems(SECOND_PAGE).getContent().getFirst().siblings()).containsExactly(104L);
    }

    private static PackageItemRepository.ItemOfLine line(Long orderItemId, Long packageItemId) {
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
}
