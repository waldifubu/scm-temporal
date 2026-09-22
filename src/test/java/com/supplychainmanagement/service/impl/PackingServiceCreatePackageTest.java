package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.CreatePackageItemsRequest;
import com.supplychainmanagement.dto.shipping.CreatePackageRequest;
import com.supplychainmanagement.dto.shipping.PackItem;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.PackageItemRepository;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Packing a line across several packages, and the two ways that used to let more be packed than was
 * ordered.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PackingServiceCreatePackageTest {

    private static final Long ORDER_NO = 1042L;
    private static final Long LINE_ID = 11L;

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private ShipmentPackageRepository shipmentPackageRepository;
    @Mock
    private PackageItemRepository packageItemRepository;

    @InjectMocks
    private PackingServiceImpl service;

    private OrderItem line;

    private OrderItem line(int orderedQuantity, FulfillmentStatus status) {
        return line(LINE_ID, orderedQuantity, status);
    }

    private OrderItem line(Long id, int orderedQuantity, FulfillmentStatus status) {
        Order order = new Order();
        order.setOrderNo(ORDER_NO);

        Product product = new Product();
        product.setArticleNo(1001L);

        OrderItem item = new OrderItem();
        item.setId(id);
        item.setOrder(order);
        item.setProduct(product);
        item.setQuantity(orderedQuantity);
        item.setFulfillmentStatus(status);

        when(orderItemRepository.findForUpdateById(id)).thenReturn(Optional.of(item));
        when(shipmentPackageRepository.save(any(ShipmentPackage.class))).thenAnswer(call -> call.getArgument(0));
        return item;
    }

    private CreatePackageItemsRequest itemsRequest(PackItem... items) {
        return new CreatePackageItemsRequest(List.of(items));
    }

    /** Nothing packed yet for any line, and saveAll hands back what it was given. */
    private void nothingPackedYet() {
        when(shipmentPackageRepository.sumQuantityByOrderItemId(anyLong())).thenReturn(0);
        when(packageItemRepository.saveAll(anyList())).thenAnswer(call -> call.getArgument(0));
    }

    private CreatePackageRequest request(PackItem... items) {
        return new CreatePackageRequest(List.of(items), ShipmentPackageType.CARTON,
                null, null, null, "PKG-TEST");
    }

    /** The everyday split: 6 now, 4 later, and the line only reaches PACKED with the second one. */
    @Test
    void packsALineAcrossTwoPackages() {
        line = line(10, FulfillmentStatus.PICKED);
        when(shipmentPackageRepository.sumQuantityByOrderItemId(LINE_ID)).thenReturn(0);

        service.createShipmentPackage(ORDER_NO, request(new PackItem(LINE_ID, 6)));
        assertThat(line.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PACKING);

        when(shipmentPackageRepository.sumQuantityByOrderItemId(LINE_ID)).thenReturn(6);
        ShipmentPackage second = service.createShipmentPackage(ORDER_NO, request(new PackItem(LINE_ID, 4)));

        assertThat(second.getItems()).extracting(PackageItem::getQuantity).containsExactly(4);
        assertThat(line.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PACKED);
    }

    /**
     * The first gap: the same line twice in one request. sumQuantityByOrderItemId asks the database,
     * which cannot see the package still being built - so both halves used to pass the check and the
     * unique constraint on package_item turned it into a 500 at flush time.
     */
    @Test
    void countsWhatTheCurrentPackageAlreadyHoldsForTheSameLine() {
        line = line(10, FulfillmentStatus.PICKED);
        when(shipmentPackageRepository.sumQuantityByOrderItemId(LINE_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.createShipmentPackage(ORDER_NO,
                request(new PackItem(LINE_ID, 8), new PackItem(LINE_ID, 8))))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Cannot pack more than ordered qty");
    }

    /**
     * The same line twice in one package request is folded into one item: two items of one line
     * and one run in the same package would violate uq_package_item_order_item_run at flush time.
     */
    @Test
    void foldsTheSameLineTwiceIntoOneItem() {
        line = line(10, FulfillmentStatus.PICKED);
        when(shipmentPackageRepository.sumQuantityByOrderItemId(LINE_ID)).thenReturn(0);

        ShipmentPackage created = service.createShipmentPackage(ORDER_NO,
                request(new PackItem(LINE_ID, 6), new PackItem(LINE_ID, 4)));

        assertThat(created.getItems()).extracting(PackageItem::getQuantity).containsExactly(10);
        assertThat(line.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PACKED);
    }

    /** The same for a package created with items but without an order number. */
    @Test
    void foldsTheSameLineTwiceIntoOneItemOfACustomShipment() {
        line = line(10, FulfillmentStatus.PICKED);
        when(shipmentPackageRepository.sumQuantityByOrderItemId(LINE_ID)).thenReturn(0);

        ShipmentPackage created = service.createCustomShipment(
                request(new PackItem(LINE_ID, 3), new PackItem(LINE_ID, 2)));

        assertThat(created.getItems()).extracting(PackageItem::getQuantity).containsExactly(5);
        assertThat(line.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PACKING);
    }

    /**
     * A package holds the items of one order only. A custom package names no order, so the lines
     * may come from anywhere - two orders in one request are refused before anything is saved.
     */
    @Test
    void refusesACustomPackageWithLinesOfTwoOrders() {
        line = line(10, FulfillmentStatus.PICKED);
        OrderItem other = line(12L, 5, FulfillmentStatus.PICKED);
        line.getOrder().setId(1L);
        other.getOrder().setId(2L);
        when(shipmentPackageRepository.sumQuantityByOrderItemId(anyLong())).thenReturn(0);

        assertThatThrownBy(() -> service.createCustomShipment(
                request(new PackItem(LINE_ID, 2), new PackItem(other.getId(), 2))))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("A package can only hold items of one order, got items of orders [1, 2]");
        verify(shipmentPackageRepository, never()).save(any());
    }

    /** Loose items are folded the same way - one item per line and run. */
    @Test
    void foldsTheSameLineTwiceIntoOneLooseItem() {
        line = line(10, FulfillmentStatus.PICKED);
        nothingPackedYet();

        List<PackageItem> created = service.createPackageItems(
                itemsRequest(new PackItem(LINE_ID, 4), new PackItem(LINE_ID, 4)));

        assertThat(created).extracting(PackageItem::getQuantity).containsExactly(8);
    }

    /**
     * The second gap is the one this cannot reach from here - two concurrent calls. What it can pin
     * down is that the line is read through the locking finder, which is what closes it.
     */
    @Test
    void readsTheLineThroughTheLockingFinder() {
        line = line(10, FulfillmentStatus.PICKED);
        when(shipmentPackageRepository.sumQuantityByOrderItemId(LINE_ID)).thenReturn(0);

        service.createShipmentPackage(ORDER_NO, request(new PackItem(LINE_ID, 10)));

        org.mockito.Mockito.verify(orderItemRepository).findForUpdateById(LINE_ID);
        org.mockito.Mockito.verify(orderItemRepository, org.mockito.Mockito.never()).findById(LINE_ID);
    }

    /** A line that is already fully packed is skipped - and an empty package is not a result. */
    @Test
    void refusesAPackageForALineThatIsAlreadyPacked() {
        line = line(10, FulfillmentStatus.PACKED);
        when(shipmentPackageRepository.sumQuantityByOrderItemId(LINE_ID)).thenReturn(10);

        assertThatThrownBy(() -> service.createShipmentPackage(ORDER_NO, request(new PackItem(LINE_ID, 1))))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("No valid items to pack");
    }

    /** After the refactoring the package still receives its items - all of them under one runNo. */
    @Test
    void putsEveryItemOfAPackageUnderOneRunNo() {
        line = line(10, FulfillmentStatus.PICKED);
        OrderItem other = line(12L, 5, FulfillmentStatus.PICKED);
        nothingPackedYet();

        ShipmentPackage created = service.createShipmentPackage(ORDER_NO,
                request(new PackItem(LINE_ID, 4), new PackItem(other.getId(), 5)));

        assertThat(created.getItems()).extracting(PackageItem::getShipmentPackage).containsOnly(created);
        assertThat(created.getItems()).extracting(PackageItem::getRunNo)
                .doesNotContainNull()
                .containsOnly(created.getItems().getFirst().getRunNo());
    }

    /** Loose items: no package, but one runNo for everything the call created - that is the run. */
    @Test
    void createsPackageItemsWithoutAPackageUnderOneRunNo() {
        line = line(10, FulfillmentStatus.PICKED);
        OrderItem other = line(12L, 5, FulfillmentStatus.PICKED);
        nothingPackedYet();

        List<PackageItem> created = service.createPackageItems(
                itemsRequest(new PackItem(other.getId(), 5), new PackItem(LINE_ID, 4)));

        assertThat(created).hasSize(2);
        assertThat(created).extracting(PackageItem::getShipmentPackage).containsOnlyNulls();
        assertThat(created).extracting(PackageItem::getRunNo)
                .doesNotContainNull()
                .containsOnly(created.getFirst().getRunNo());
        assertThat(line.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PACKING);
        assertThat(other.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PACKED);
    }

    /** A second call is a second run. */
    @Test
    void givesEveryCallItsOwnRunNo() {
        line = line(10, FulfillmentStatus.PICKED);
        nothingPackedYet();

        UUID first = service.createPackageItems(itemsRequest(new PackItem(LINE_ID, 2))).getFirst().getRunNo();
        UUID second = service.createPackageItems(itemsRequest(new PackItem(LINE_ID, 2))).getFirst().getRunNo();

        assertThat(first).isNotEqualTo(second);
    }

    /** The same guard as for a package: what the run already holds counts against the ordered quantity. */
    @Test
    void countsWhatTheRunAlreadyHoldsForTheSameLine() {
        line = line(10, FulfillmentStatus.PICKED);
        nothingPackedYet();

        assertThatThrownBy(() -> service.createPackageItems(
                itemsRequest(new PackItem(LINE_ID, 8), new PackItem(LINE_ID, 8))))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Cannot pack more than ordered qty");

        verify(packageItemRepository, never()).saveAll(anyList());
    }

    /** A line not yet picked is skipped - and a run that packed nothing is refused, not saved empty. */
    @Test
    void refusesARunWithNoLineReadyToPack() {
        line = line(10, FulfillmentStatus.RESERVED);
        nothingPackedYet();

        assertThatThrownBy(() -> service.createPackageItems(itemsRequest(new PackItem(LINE_ID, 1))))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("No valid items to pack");

        verify(packageItemRepository, never()).saveAll(anyList());
    }

    /**
     * The over-packing guard counts every package item of the line, in a package or loose: 8 already
     * packed elsewhere leave room for 2 of the 10 ordered, not for 3.
     */
    @Test
    void refusesWhatOtherPackageItemsOfTheLineLeaveNoRoomFor() {
        line = line(10, FulfillmentStatus.PACKING);
        nothingPackedYet();
        when(shipmentPackageRepository.sumQuantityByOrderItemId(LINE_ID)).thenReturn(8);

        assertThatThrownBy(() -> service.createPackageItems(itemsRequest(new PackItem(LINE_ID, 3))))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("already packed: 8");

        verify(packageItemRepository, never()).saveAll(anyList());
    }

    /** A quantity of 0 is an error - even on a line that would otherwise just be skipped. */
    @Test
    void rejectsAQuantityOfZeroEvenForALineNotReadyToPack() {
        line = line(10, FulfillmentStatus.RESERVED);
        nothingPackedYet();

        assertThatThrownBy(() -> service.createPackageItems(itemsRequest(new PackItem(LINE_ID, 0))))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Quantity must be at least 1");
    }

    /** One invalid quantity rejects the whole request before a single line is locked or changed. */
    @Test
    void rejectsTheWholeRequestBeforeTouchingAnyLine() {
        line = line(10, FulfillmentStatus.PICKED);
        nothingPackedYet();

        assertThatThrownBy(() -> service.createShipmentPackage(ORDER_NO,
                request(new PackItem(LINE_ID, 4), new PackItem(12L, -1))))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("was: -1");

        verify(orderItemRepository, never()).findForUpdateById(anyLong());
        assertThat(line.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PICKED);
    }

    /** A missing quantity is rejected as well, instead of failing on unboxing. */
    @Test
    void rejectsAMissingQuantity() {
        assertThatThrownBy(() -> service.createPackageItems(itemsRequest(new PackItem(LINE_ID, null))))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("was: null");
    }

    /**
     * 10 ordered, the request says 5, 5, 5: the first two fill the line, the third finds nothing left
     * and is skipped - not rejected. The two packed entries end up as one item of 10.
     */
    @Test
    void skipsARepeatedLineOnceTheRunHasFilledIt() {
        line = line(10, FulfillmentStatus.PICKED);
        nothingPackedYet();

        List<PackageItem> created = service.createPackageItems(itemsRequest(
                new PackItem(LINE_ID, 5), new PackItem(LINE_ID, 5), new PackItem(LINE_ID, 5)));

        assertThat(created).extracting(PackageItem::getQuantity).containsExactly(10);
        assertThat(line.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PACKED);
    }

    /**
     * Already filled by earlier packages (5 + 5 of 10): the line is skipped by its quantities even
     * where its status still reads PACKING, and the rest of the request is packed as usual.
     */
    @Test
    void skipsALineEarlierPackagesHaveAlreadyFilled() {
        line = line(10, FulfillmentStatus.PACKING);
        OrderItem other = line(12L, 5, FulfillmentStatus.PICKED);
        nothingPackedYet();
        when(shipmentPackageRepository.sumQuantityByOrderItemId(LINE_ID)).thenReturn(10);

        ShipmentPackage created = service.createShipmentPackage(ORDER_NO,
                request(new PackItem(LINE_ID, 5), new PackItem(other.getId(), 5)));

        assertThat(created.getItems())
                .extracting(packageItem -> packageItem.getOrderItem().getId())
                .containsExactly(other.getId());
    }

    /** Some room left but asked for more than that is still an error - only a full line is skipped. */
    @Test
    void stillRefusesMoreThanTheRoomLeft() {
        line = line(10, FulfillmentStatus.PACKING);
        nothingPackedYet();
        when(shipmentPackageRepository.sumQuantityByOrderItemId(LINE_ID)).thenReturn(9);

        assertThatThrownBy(() -> service.createPackageItems(itemsRequest(new PackItem(LINE_ID, 5))))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("already packed: 9");
    }

    /**
     * Quantities before status: the line already reads PACKED although only 9 of 10 are packed.
     * Asking for 5 is still reported as an error instead of being skipped on the status.
     */
    @Test
    void reportsAnOverflowEvenWhenTheStatusAlreadyReadsPacked() {
        line = line(10, FulfillmentStatus.PACKED);
        nothingPackedYet();
        when(shipmentPackageRepository.sumQuantityByOrderItemId(LINE_ID)).thenReturn(9);

        assertThatThrownBy(() -> service.createPackageItems(itemsRequest(new PackItem(LINE_ID, 5))))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("already packed: 9");
    }

    /** The same for a line not picked yet: more than was ordered is an error, not a skip. */
    @Test
    void reportsAnOverflowEvenForALineNotReadyToPack() {
        line = line(10, FulfillmentStatus.RESERVED);
        nothingPackedYet();

        assertThatThrownBy(() -> service.createShipmentPackage(ORDER_NO, request(new PackItem(LINE_ID, 11))))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("requested: 11");
    }

    /** An order item that does not exist is a 404 - GlobalExceptionHandler maps ResourceNotFoundException. */
    @Test
    void answersAnUnknownOrderItemWithNotFound() {
        nothingPackedYet();
        when(orderItemRepository.findForUpdateById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPackageItems(itemsRequest(new PackItem(99L, 1))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("OrderItem not found with id: 99");
    }

    /** A line of a different order in the package request of this order is the caller's mistake: 400. */
    @Test
    void refusesALineOfAnotherOrder() {
        line = line(10, FulfillmentStatus.PICKED);
        nothingPackedYet();

        assertThatThrownBy(() -> service.createShipmentPackage(4711L, request(new PackItem(LINE_ID, 1))))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("does not belong to order 4711");

        assertThat(line.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PICKED);
    }

    /** No items needed: the package is created on its own, with the same defaults as a packed one. */
    @Test
    void createsAnEmptyPackageWithoutItems() {
        when(shipmentPackageRepository.save(any(ShipmentPackage.class))).thenAnswer(call -> call.getArgument(0));

        ShipmentPackage created = service.createCustomShipment(
                new CreatePackageRequest(null, null, null, null, null, null));

        assertThat(created.getItems()).isEmpty();
        assertThat(created.getShipmentPackageStatus()).isEqualTo(ShipmentPackageStatus.OPEN);
        assertThat(created.getShipmentPackageType()).isEqualTo(ShipmentPackageType.OTHER);
        assertThat(created.getPackageNumber()).startsWith("PKG-");
        verify(orderItemRepository, never()).findForUpdateById(anyLong());
    }

    /** Both ways of creating a package set it up alike - they share one method for it. */
    @Test
    void setsUpAnEmptyPackageLikeAPackedOne() {
        line = line(10, FulfillmentStatus.PICKED);
        nothingPackedYet();

        ShipmentPackage packed = service.createShipmentPackage(ORDER_NO, request(new PackItem(LINE_ID, 1)));
        ShipmentPackage empty = service.createCustomShipment(request());

        assertThat(empty)
                .extracting(ShipmentPackage::getShipmentPackageStatus, ShipmentPackage::getShipmentPackageType,
                        ShipmentPackage::getPackageNumber)
                .containsExactly(packed.getShipmentPackageStatus(), packed.getShipmentPackageType(),
                        packed.getPackageNumber());
    }

    /**
     * What validateOrderItemPacking used to guard in createPackageItems: a line that is PACKED or
     * READY_FOR_DISPATCH must never receive another package item. packLine holds that on its own -
     * even for a line whose status got there without package items (the former manual "complete"
     * endpoint set PACKED directly), so the quantity check alone would still see room.
     */
    @ParameterizedTest
    @EnumSource(value = FulfillmentStatus.class, names = {"PACKED", "READY_FOR_DISPATCH"})
    void neverPacksALinePastPackingEvenWithoutPackageItems(FulfillmentStatus status) {
        line = line(10, status);
        nothingPackedYet();

        assertThatThrownBy(() -> service.createPackageItems(itemsRequest(new PackItem(LINE_ID, 1))))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("No valid items to pack")
                .hasMessageContaining("OrderItem 11 is " + status + ", only PICKED or PACKING can be packed");

        verify(packageItemRepository, never()).saveAll(anyList());
        assertThat(line.getFulfillmentStatus()).isEqualTo(status);
    }

    /** Next to a line that can be packed, a line past PACKING is skipped - and its status left alone. */
    @ParameterizedTest
    @EnumSource(value = FulfillmentStatus.class, names = {"PACKED", "READY_FOR_DISPATCH"})
    void skipsALinePastPackingNextToOneThatCanBePacked(FulfillmentStatus status) {
        line = line(10, status);
        OrderItem other = line(12L, 5, FulfillmentStatus.PICKED);
        nothingPackedYet();

        List<PackageItem> created = service.createPackageItems(
                itemsRequest(new PackItem(LINE_ID, 1), new PackItem(other.getId(), 5)));

        assertThat(created).extracting(item -> item.getOrderItem().getId()).containsExactly(other.getId());
        assertThat(line.getFulfillmentStatus()).isEqualTo(status);
    }

    /**
     * Nothing packed at all: the error names every skipped line and why - one not picked yet, one
     * already full - instead of a bare "No valid items to pack".
     */
    @Test
    void namesEverySkippedLineWithItsReason() {
        line = line(10, FulfillmentStatus.RESERVED);
        OrderItem full = line(12L, 5, FulfillmentStatus.PACKING);
        nothingPackedYet();
        when(shipmentPackageRepository.sumQuantityByOrderItemId(12L)).thenReturn(5);

        assertThatThrownBy(() -> service.createShipmentPackage(ORDER_NO,
                request(new PackItem(LINE_ID, 1), new PackItem(full.getId(), 1))))
                .isInstanceOf(APIException.class)
                .hasMessage("No valid items to pack for order 1042: "
                        + "OrderItem 11 is RESERVED, only PICKED or PACKING can be packed; "
                        + "OrderItem 12 is already fully packed (5 of 5)");
    }

    /** The same line skipped twice in one request is named once. */
    @Test
    void namesARepeatedSkippedLineOnce() {
        line = line(10, FulfillmentStatus.READY_FOR_DISPATCH);
        nothingPackedYet();
        when(shipmentPackageRepository.sumQuantityByOrderItemId(LINE_ID)).thenReturn(10);

        assertThatThrownBy(() -> service.createPackageItems(
                itemsRequest(new PackItem(LINE_ID, 1), new PackItem(LINE_ID, 1))))
                .isInstanceOf(APIException.class)
                .hasMessage("No valid items to pack: OrderItem 11 is already fully packed (10 of 10)");
    }
}
