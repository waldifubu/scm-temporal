package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.CreatePackageFromItemsRequest;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.PackageItemRepository;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A package made of items that already exist, in one step: {@code createPackageFromItems}. The rules
 * are the ones {@code addPackageItems} applies to an existing package; what is new here is that the
 * package does not exist yet, so a refusal must leave nothing behind - which a mock shows as "never
 * saved".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PackingServiceCreateFromItemsTest {

    private static final Long ORDER_ID = 42L;

    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private ShipmentPackageRepository shipmentPackageRepository;
    @Mock
    private PackageItemRepository packageItemRepository;

    @InjectMocks
    private PackingServiceImpl service;

    private static PackageItem item(Long id, Long orderItemId, Long orderId, UUID runNo) {
        Order order = new Order();
        order.setId(orderId);
        Product product = new Product();
        product.setWeight(BigDecimal.ONE);

        OrderItem line = new OrderItem();
        line.setId(orderItemId);
        line.setOrder(order);
        line.setProduct(product);

        PackageItem item = new PackageItem();
        item.setId(id);
        item.setOrderItem(line);
        item.setQuantity(2);
        item.setRunNo(runNo);
        return item;
    }

    private static PackageItem item(Long id, Long orderItemId, Long orderId) {
        return item(id, orderItemId, orderId, UUID.randomUUID());
    }

    /** The locking finder answers like the database: only the ids it knows, in ascending order. */
    private void storedItems(PackageItem... items) {
        when(packageItemRepository.findAllForUpdateByIdIn(anyCollection())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return Stream.of(items)
                    .filter(item -> ids.contains(item.getId()))
                    .sorted(Comparator.comparing(PackageItem::getId))
                    .toList();
        });
        when(shipmentPackageRepository.save(any(ShipmentPackage.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static CreatePackageFromItemsRequest request(Long... ids) {
        return new CreatePackageFromItemsRequest(List.of(ids), null, null, null, null, null);
    }

    private static CreatePackageFromItemsRequest request(ShipmentPackageType type, String number, Long... ids) {
        return new CreatePackageFromItemsRequest(List.of(ids), type, new BigDecimal("30"), new BigDecimal("20"),
                new BigDecimal("10"), number);
    }

    private APIException refused(CreatePackageFromItemsRequest request) {
        return catchThrowableOfType(APIException.class, () -> service.createPackageFromItems(request));
    }

    // ------------------------------------------------------------------ what it makes

    @Test
    void makesAnOpenPackageHoldingTheItems() {
        PackageItem first = item(1L, 11L, ORDER_ID);
        PackageItem second = item(2L, 12L, ORDER_ID);
        storedItems(first, second);

        ShipmentPackage created = service.createPackageFromItems(request(1L, 2L));

        assertThat(created.getShipmentPackageStatus()).isEqualTo(ShipmentPackageStatus.OPEN);
        assertThat(created.getItems()).extracting(PackageItem::getId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(first.getShipmentPackage()).isSameAs(created);
        assertThat(second.getShipmentPackage()).isSameAs(created);
        verify(shipmentPackageRepository).save(created);
    }

    /** Never sent, always computed: unit weight 1 x (2 + 2) items. */
    @Test
    void computesTheWeightFromWhatItHolds() {
        storedItems(item(1L, 11L, ORDER_ID), item(2L, 12L, ORDER_ID));

        ShipmentPackage created = service.createPackageFromItems(request(1L, 2L));

        assertThat(created.getWeight()).isEqualByComparingTo("4");
    }

    @Test
    void takesTypeDimensionsAndNumberAsSent() {
        storedItems(item(1L, 11L, ORDER_ID));

        ShipmentPackage created = service.createPackageFromItems(request(ShipmentPackageType.CARTON, "MY-PKG-1", 1L));

        assertThat(created.getShipmentPackageType()).isEqualTo(ShipmentPackageType.CARTON);
        assertThat(created.getPackageNumber()).isEqualTo("MY-PKG-1");
        assertThat(created.getLength()).isEqualByComparingTo("30");
        assertThat(created.getWidth()).isEqualByComparingTo("20");
        assertThat(created.getHeight()).isEqualByComparingTo("10");
    }

    /** The JSON of the caller: type, number and the dimensions all null. */
    @Test
    void fillsInTheDefaultsForWhatIsNull() {
        storedItems(item(1L, 11L, ORDER_ID));

        ShipmentPackage created = service.createPackageFromItems(request(1L));

        assertThat(created.getShipmentPackageType()).isEqualTo(ShipmentPackageType.OTHER);
        assertThat(created.getPackageNumber()).startsWith("PKG-");
        assertThat(created.getLength()).isNull();
        assertThat(created.getWidth()).isNull();
        assertThat(created.getHeight()).isNull();
    }

    @Test
    void takesARepeatedIdOnce() {
        storedItems(item(1L, 11L, ORDER_ID));

        ShipmentPackage created = service.createPackageFromItems(request(1L, 1L, 1L));

        assertThat(created.getItems()).hasSize(1);
    }

    // ------------------------------------------------------------------ what it refuses, and leaves no package

    @Test
    void refusesAnEmptyListWith400() {
        APIException e = refused(new CreatePackageFromItemsRequest(List.of(), null, null, null, null, null));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(shipmentPackageRepository, never()).save(any());
    }

    @Test
    void refusesAMissingListWith400() {
        APIException e = refused(new CreatePackageFromItemsRequest(null, null, null, null, null, null));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(packageItemRepository, never()).findAllForUpdateByIdIn(anyCollection());
    }

    @Test
    void namesEveryUnknownIdWith404() {
        storedItems(item(1L, 11L, ORDER_ID));

        APIException e = refused(request(1L, 7L, 9L));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.getMessage()).contains("7").contains("9");
        verify(shipmentPackageRepository, never()).save(any());
    }

    /** Never taken silently out of the package it is in. */
    @Test
    void refusesAnItemThatIsAlreadyInAPackageWith409() {
        PackageItem taken = item(2L, 12L, ORDER_ID);
        ShipmentPackage other = new ShipmentPackage();
        other.setId(8L);
        other.addItem(taken);
        storedItems(item(1L, 11L, ORDER_ID), taken);

        APIException e = refused(request(1L, 2L));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getMessage()).contains("PackageItem 2").contains("ShipmentPackage 8");
        assertThat(taken.getShipmentPackage()).isSameAs(other);
        verify(shipmentPackageRepository, never()).save(any());
    }

    /** A package goes to one customer. */
    @Test
    void refusesItemsOfTwoOrdersWith400() {
        PackageItem first = item(1L, 11L, ORDER_ID);
        PackageItem second = item(2L, 12L, 43L);
        storedItems(first, second);

        APIException e = refused(request(1L, 2L));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getMessage()).contains("one order");
        // Nothing was attached before the refusal.
        assertThat(first.getShipmentPackage()).isNull();
        assertThat(second.getShipmentPackage()).isNull();
        verify(shipmentPackageRepository, never()).save(any());
    }

    /** uq_package_item_order_item_run: one item per line and run in a package. */
    @Test
    void refusesTwoItemsOfOneLineFromOneRunWith409() {
        UUID run = UUID.randomUUID();
        storedItems(item(1L, 11L, ORDER_ID, run), item(2L, 11L, ORDER_ID, run));

        APIException e = refused(request(1L, 2L));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getMessage()).contains("the new package").doesNotContain("null");
        verify(shipmentPackageRepository, never()).save(any());
    }

    /** The same line from two runs (5 + 5 packed in two goes) may share a package. */
    @Test
    void takesTwoItemsOfOneLineFromTwoRuns() {
        storedItems(item(1L, 11L, ORDER_ID, UUID.randomUUID()), item(2L, 11L, ORDER_ID, UUID.randomUUID()));

        ShipmentPackage created = service.createPackageFromItems(request(1L, 2L));

        assertThat(created.getItems()).hasSize(2);
    }
}
