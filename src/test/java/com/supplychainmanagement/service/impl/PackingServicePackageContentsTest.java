package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.PackageItemIdsRequest;
import com.supplychainmanagement.dto.shipping.UpdatePackageRequest;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Changing what an existing package holds: adding loose items, replacing the contents, taking one
 * item out, and updating the package's own data. Items move between "loose" and "in a package" -
 * they are never deleted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PackingServicePackageContentsTest {

    private static final Long PACKAGE_ID = 5L;
    private static final Long ORDER_ID = 42L;

    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private ShipmentPackageRepository shipmentPackageRepository;
    @Mock
    private PackageItemRepository packageItemRepository;

    @InjectMocks
    private PackingServiceImpl service;

    private ShipmentPackage shipmentPackage(Long id, ShipmentPackageStatus status) {
        ShipmentPackage shipmentPackage = new ShipmentPackage();
        shipmentPackage.setId(id);
        shipmentPackage.setShipmentPackageStatus(status);

        when(shipmentPackageRepository.findForUpdateById(id)).thenReturn(Optional.of(shipmentPackage));
        when(shipmentPackageRepository.save(any(ShipmentPackage.class))).thenAnswer(call -> call.getArgument(0));
        return shipmentPackage;
    }

    private ShipmentPackage openPackage() {
        return shipmentPackage(PACKAGE_ID, ShipmentPackageStatus.OPEN);
    }

    /** An item from a run of its own - every call gets a fresh runNo. */
    private static PackageItem item(Long id, Long orderItemId, Long orderId) {
        return item(id, orderItemId, orderId, UUID.randomUUID());
    }

    private static PackageItem item(Long id, Long orderItemId, Long orderId, UUID runNo) {
        Order order = new Order();
        order.setId(orderId);

        OrderItem line = new OrderItem();
        line.setId(orderItemId);
        line.setOrder(order);

        PackageItem item = new PackageItem();
        item.setId(id);
        item.setOrderItem(line);
        item.setQuantity(1);
        item.setRunNo(runNo);
        return item;
    }

    /** The item's order line as it reads once all of it is packed. */
    private static PackageItem fullyPacked(PackageItem item) {
        item.getOrderItem().setFulfillmentStatus(FulfillmentStatus.PACKED);
        return item;
    }

    /** Gives the item's product a unit weight and the item a quantity. */
    private static PackageItem weighing(PackageItem item, String unitWeight, int quantity) {
        Product product = new Product();
        product.setWeight(new BigDecimal(unitWeight));
        item.getOrderItem().setProduct(product);
        item.setQuantity(quantity);
        return item;
    }

    private static PackageItem in(ShipmentPackage shipmentPackage, PackageItem item) {
        shipmentPackage.addItem(item);
        return item;
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
    }

    private static PackageItemIdsRequest ids(Long... ids) {
        return new PackageItemIdsRequest(List.of(ids));
    }

    private static List<Long> idsIn(ShipmentPackage shipmentPackage) {
        return shipmentPackage.getItems().stream().map(PackageItem::getId).toList();
    }

    // ------------------------------------------------------------------ add

    @Test
    void addsLooseItemsToThePackage() {
        ShipmentPackage shipmentPackage = openPackage();
        PackageItem first = item(101L, 11L, ORDER_ID);
        PackageItem second = item(102L, 12L, ORDER_ID);
        storedItems(first, second);

        service.addPackageItems(PACKAGE_ID, ids(101L, 102L));

        assertThat(idsIn(shipmentPackage)).containsExactly(101L, 102L);
        assertThat(first.getShipmentPackage()).isSameAs(shipmentPackage);
        assertThat(second.getShipmentPackage()).isSameAs(shipmentPackage);
    }

    /** Repeating a call changes nothing: an item already in the package is not added twice. */
    @Test
    void leavesAnItemAlreadyInThePackageAsItIs() {
        ShipmentPackage shipmentPackage = openPackage();
        PackageItem already = in(shipmentPackage, item(101L, 11L, ORDER_ID));
        PackageItem loose = item(102L, 12L, ORDER_ID);
        storedItems(already, loose);

        service.addPackageItems(PACKAGE_ID, ids(101L, 102L));

        assertThat(idsIn(shipmentPackage)).containsExactly(101L, 102L);
    }

    /** Never taken silently out of another package. */
    @Test
    void refusesAnItemThatIsInAnotherPackage() {
        openPackage();
        ShipmentPackage other = shipmentPackage(6L, ShipmentPackageStatus.OPEN);
        PackageItem elsewhere = in(other, item(101L, 11L, ORDER_ID));
        storedItems(elsewhere);

        assertThatThrownBy(() -> service.addPackageItems(PACKAGE_ID, ids(101L)))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("PackageItem 101 is already in ShipmentPackage 6");

        assertThat(elsewhere.getShipmentPackage()).isSameAs(other);
    }

    @Test
    void refusesItemsOfASecondOrder() {
        ShipmentPackage shipmentPackage = openPackage();
        in(shipmentPackage, item(101L, 11L, ORDER_ID));
        PackageItem foreign = item(102L, 21L, 43L);
        storedItems(foreign);

        assertThatThrownBy(() -> service.addPackageItems(PACKAGE_ID, ids(102L)))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("can only hold items of one order, got items of orders [42, 43]");

        assertThat(foreign.getShipmentPackage()).isNull();
    }

    /**
     * Two runs that packed the same line, 5 + 5, leave two loose items. Both go into one package - the
     * line's quantity there is the sum, and each item keeps its own runNo.
     */
    @Test
    void takesTwoItemsOfTheSameLineFromDifferentRuns() {
        ShipmentPackage shipmentPackage = openPackage();
        in(shipmentPackage, item(101L, 11L, ORDER_ID, UUID.randomUUID()));
        PackageItem fromAnotherRun = item(103L, 11L, ORDER_ID, UUID.randomUUID());
        storedItems(fromAnotherRun);

        service.addPackageItems(PACKAGE_ID, ids(103L));

        assertThat(idsIn(shipmentPackage)).containsExactly(101L, 103L);
    }

    /** The same line twice from the same run is what the unique constraint still forbids. */
    @Test
    void refusesTwoItemsOfTheSameLineFromTheSameRun() {
        UUID run = UUID.fromString("0f8c3a52-2d6e-4c43-9e0b-6a1c1f0f7b11");
        ShipmentPackage shipmentPackage = openPackage();
        in(shipmentPackage, item(101L, 11L, ORDER_ID, run));
        storedItems(item(102L, 11L, ORDER_ID, run));

        assertThatThrownBy(() -> service.addPackageItems(PACKAGE_ID, ids(102L)))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("OrderItem 11 from run " + run
                        + " would be in ShipmentPackage 5 twice, as package items [101, 102]");
    }

    @Test
    void refusesToAddNothing() {
        openPackage();

        assertThatThrownBy(() -> service.addPackageItems(PACKAGE_ID, ids()))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(packageItemRepository, never()).findAllForUpdateByIdIn(anyCollection());
    }

    @Test
    void namesEveryPackageItemThatDoesNotExist() {
        openPackage();
        storedItems(item(101L, 11L, ORDER_ID));

        assertThatThrownBy(() -> service.addPackageItems(PACKAGE_ID, ids(998L, 101L, 999L)))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessage("PackageItem not found: [998, 999]");
    }

    @Test
    void changesOnlyAnOpenPackage() {
        shipmentPackage(PACKAGE_ID, ShipmentPackageStatus.PACKED);

        assertThatThrownBy(() -> service.addPackageItems(PACKAGE_ID, ids(101L)))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("ShipmentPackage 5 is PACKED, only an OPEN package can be changed");
    }

    /**
     * Once closed, a package is fixed: no item goes in, none comes out, its data stays and it is not
     * completed twice. The same guard for every way in - findOpenPackageForUpdate.
     */
    @ParameterizedTest
    @EnumSource(value = ShipmentPackageStatus.class, names = {"PACKED", "DISPATCHED"})
    void keepsItsItemsOnceItIsClosed(ShipmentPackageStatus status) {
        ShipmentPackage closed = shipmentPackage(PACKAGE_ID, status);
        PackageItem held = in(closed, item(101L, 11L, ORDER_ID));
        storedItems(held, item(102L, 12L, ORDER_ID));

        assertThatThrownBy(() -> service.addPackageItems(PACKAGE_ID, ids(102L)))
                .isInstanceOfSatisfying(APIException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.updateCustomShipment(PACKAGE_ID, ids(102L)))
                .isInstanceOfSatisfying(APIException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.removePackageItem(PACKAGE_ID, 101L))
                .isInstanceOfSatisfying(APIException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.updatePackageData(PACKAGE_ID,
                new UpdatePackageRequest(ShipmentPackageType.CARTON, null, null, null, "PKG-9")))
                .isInstanceOfSatisfying(APIException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.completePackage(PACKAGE_ID))
                .isInstanceOfSatisfying(APIException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(idsIn(closed)).containsExactly(101L);
        assertThat(held.getShipmentPackage()).isSameAs(closed);
        verify(shipmentPackageRepository, never()).save(any());
    }

    @Test
    void answersAnUnknownPackageWithNotFound() {
        when(shipmentPackageRepository.findForUpdateById(PACKAGE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addPackageItems(PACKAGE_ID, ids(101L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ replace

    /** What is left out goes back to being loose - not deleted. */
    @Test
    void replacesTheContentsAndLetsTheRestGoLoose() {
        ShipmentPackage shipmentPackage = openPackage();
        PackageItem leaving = in(shipmentPackage, item(101L, 11L, ORDER_ID));
        PackageItem staying = in(shipmentPackage, item(102L, 12L, ORDER_ID));
        PackageItem arriving = item(103L, 13L, ORDER_ID);
        storedItems(leaving, staying, arriving);

        service.updateCustomShipment(PACKAGE_ID, ids(102L, 103L));

        assertThat(idsIn(shipmentPackage)).containsExactlyInAnyOrder(102L, 103L);
        assertThat(leaving.getShipmentPackage()).isNull();
        assertThat(arriving.getShipmentPackage()).isSameAs(shipmentPackage);
        verify(packageItemRepository, never()).delete(any());
        verify(packageItemRepository, never()).deleteAll(any());
    }

    @Test
    void emptiesThePackageWithAnEmptyList() {
        ShipmentPackage shipmentPackage = openPackage();
        PackageItem held = in(shipmentPackage, item(101L, 11L, ORDER_ID));

        service.updateCustomShipment(PACKAGE_ID, ids());

        assertThat(shipmentPackage.getItems()).isEmpty();
        assertThat(held.getShipmentPackage()).isNull();
        verify(packageItemRepository, never()).findAllForUpdateByIdIn(anyCollection());
    }

    /**
     * Swapping one item of a line for another item of the same line: the leaving one is written
     * before the arriving one is attached, so the unique constraint on (package, order line) never
     * sees both at once.
     */
    @Test
    void writesTheLeavingItemsBeforeAttachingNewOnes() {
        ShipmentPackage shipmentPackage = openPackage();
        PackageItem leaving = in(shipmentPackage, item(101L, 11L, ORDER_ID));
        PackageItem arriving = item(103L, 11L, ORDER_ID);
        storedItems(leaving, arriving);

        doAnswer(call -> {
            assertThat(leaving.getShipmentPackage()).isNull();
            assertThat(arriving.getShipmentPackage()).isNull();
            return null;
        }).when(packageItemRepository).flush();

        service.updateCustomShipment(PACKAGE_ID, ids(103L));

        verify(packageItemRepository).flush();
        assertThat(idsIn(shipmentPackage)).containsExactly(103L);
    }

    @Test
    void replacingKeepsTheOneOrderRule() {
        openPackage();
        storedItems(item(101L, 11L, ORDER_ID), item(102L, 21L, 43L));

        assertThatThrownBy(() -> service.updateCustomShipment(PACKAGE_ID, ids(101L, 102L)))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ------------------------------------------------------------------ remove

    @Test
    void takesOneItemOutAndLetsItGoLoose() {
        ShipmentPackage shipmentPackage = openPackage();
        PackageItem removed = in(shipmentPackage, item(101L, 11L, ORDER_ID));
        in(shipmentPackage, item(102L, 12L, ORDER_ID));
        storedItems(removed);

        service.removePackageItem(PACKAGE_ID, 101L);

        assertThat(idsIn(shipmentPackage)).containsExactly(102L);
        assertThat(removed.getShipmentPackage()).isNull();
        verify(packageItemRepository, never()).delete(any());
    }

    @Test
    void refusesToRemoveAnItemThePackageDoesNotHold() {
        openPackage();
        storedItems(item(101L, 11L, ORDER_ID));

        assertThatThrownBy(() -> service.removePackageItem(PACKAGE_ID, 101L))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("PackageItem 101 is not in ShipmentPackage 5");
    }

    // ------------------------------------------------------------------ weight

    /** The weight follows the contents: adding an item adds its weight. */
    @Test
    void addingItemsAddsTheirWeight() {
        ShipmentPackage shipmentPackage = openPackage();
        in(shipmentPackage, weighing(item(101L, 11L, ORDER_ID), "2", 1));
        PackageItem loose = weighing(item(102L, 12L, ORDER_ID), "0.5", 3);
        storedItems(loose);

        service.addPackageItems(PACKAGE_ID, ids(102L));

        assertThat(shipmentPackage.getWeight()).isEqualByComparingTo("3.5");
    }

    /** Taking the last item out leaves an empty package, and an empty package weighs 0. */
    @Test
    void anEmptiedPackageWeighsNothing() {
        ShipmentPackage shipmentPackage = openPackage();
        PackageItem held = in(shipmentPackage, weighing(item(101L, 11L, ORDER_ID), "2", 4));
        storedItems(held);

        service.removePackageItem(PACKAGE_ID, 101L);

        assertThat(shipmentPackage.getWeight()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void replacingTheContentsTakesTheWeightOfTheNewOnes() {
        ShipmentPackage shipmentPackage = openPackage();
        PackageItem leaving = in(shipmentPackage, weighing(item(101L, 11L, ORDER_ID), "5", 1));
        PackageItem arriving = weighing(item(102L, 12L, ORDER_ID), "1.25", 2);
        storedItems(leaving, arriving);

        service.updateCustomShipment(PACKAGE_ID, ids(102L));

        assertThat(shipmentPackage.getWeight()).isEqualByComparingTo("2.5");
    }

    // ------------------------------------------------------------------ complete

    /** Completing closes the package: PACKED, stamped, saved - from now on it can go into a shipment. */
    @Test
    void completesAnOpenPackage() {
        ShipmentPackage shipmentPackage = openPackage();
        in(shipmentPackage, fullyPacked(item(101L, 11L, ORDER_ID)));

        ShipmentPackage completed = service.completePackage(PACKAGE_ID);

        assertThat(completed.getShipmentPackageStatus()).isEqualTo(ShipmentPackageStatus.PACKED);
        assertThat(completed.getPackedAt()).isNotNull();
        verify(shipmentPackageRepository).save(shipmentPackage);
    }

    /**
     * Only an OPEN package is completed. The service checks that before complete() is called, so the
     * client gets a 409 - not the IllegalStateException complete() itself would throw, which ends as a
     * 500.
     */
    @Test
    void completesAnOpenPackageOnly() {
        shipmentPackage(PACKAGE_ID, ShipmentPackageStatus.PACKED);

        assertThatThrownBy(() -> service.completePackage(PACKAGE_ID))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("only an OPEN package can be changed");
        verify(shipmentPackageRepository, never()).save(any());
    }

    /** One order per package: an OPEN package holding items of two orders is not completed. */
    @Test
    void neverCompletesAPackageWithItemsOfTwoOrders() {
        ShipmentPackage shipmentPackage = openPackage();
        in(shipmentPackage, fullyPacked(item(101L, 11L, ORDER_ID)));
        in(shipmentPackage, fullyPacked(item(102L, 12L, 43L)));

        assertThatThrownBy(() -> service.completePackage(PACKAGE_ID))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("ShipmentPackage 5 can only hold items of one order, got items of orders [42, 43]");
        assertThat(shipmentPackage.getShipmentPackageStatus()).isEqualTo(ShipmentPackageStatus.OPEN);
        verify(shipmentPackageRepository, never()).save(any());
    }

    @Test
    void answersAnUnknownPackageToCompleteWith404() {
        when(shipmentPackageRepository.findForUpdateById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completePackage(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * Never without items: an empty package goes to no customer, and once PACKED it could not be
     * filled any more. Refused with 400 and left OPEN.
     */
    @Test
    void neverCompletesAnEmptyPackage() {
        ShipmentPackage shipmentPackage = openPackage();

        assertThatThrownBy(() -> service.completePackage(PACKAGE_ID))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("holds no items");
        assertThat(shipmentPackage.getShipmentPackageStatus()).isEqualTo(ShipmentPackageStatus.OPEN);
        verify(shipmentPackageRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ package data

    /**
     * Same defaults as a new package; only the printed number is kept when left out. The weight is
     * not part of the request, and changing the package data leaves it alone - it is the weight of
     * what the package holds, and that did not change.
     */
    @Test
    void updatesThePackageDataWithTheDefaultsOfANewPackage() {
        ShipmentPackage shipmentPackage = openPackage();
        shipmentPackage.setPackageNumber("PKG-1");
        shipmentPackage.setShipmentPackageType(ShipmentPackageType.CARTON);
        PackageItem held = in(shipmentPackage, weighing(item(101L, 11L, ORDER_ID), "1.5", 2));

        service.updatePackageData(PACKAGE_ID, new UpdatePackageRequest(null,
                new BigDecimal("40"), new BigDecimal("30"), new BigDecimal("20"), null));

        assertThat(shipmentPackage.getShipmentPackageType()).isEqualTo(ShipmentPackageType.OTHER);
        assertThat(shipmentPackage.getWeight()).isEqualByComparingTo("3.0");
        assertThat(shipmentPackage.getLength()).isEqualByComparingTo("40");
        assertThat(shipmentPackage.getPackageNumber()).isEqualTo("PKG-1");
        assertThat(shipmentPackage.getItems()).containsExactly(held);
    }

    @Test
    void takesANewPackageNumberWhenOneIsGiven() {
        ShipmentPackage shipmentPackage = openPackage();
        shipmentPackage.setPackageNumber("PKG-1");

        service.updatePackageData(PACKAGE_ID, new UpdatePackageRequest(
                ShipmentPackageType.CARTON, null, null, null, "PKG-2"));

        assertThat(shipmentPackage.getPackageNumber()).isEqualTo("PKG-2");
    }

    @Test
    void updatesTheDataOfAnOpenPackageOnly() {
        shipmentPackage(PACKAGE_ID, ShipmentPackageStatus.DISPATCHED);

        assertThatThrownBy(() -> service.updatePackageData(PACKAGE_ID,
                new UpdatePackageRequest(null, null, null, null, null)))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
