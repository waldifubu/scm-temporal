package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.CreateShipmentRequest;
import com.supplychainmanagement.dto.shipping.ShipmentPackageIdsRequest;
import com.supplychainmanagement.dto.shipping.ShipmentResponse;
import com.supplychainmanagement.dto.shipping.UpdateShipmentRequest;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Manager;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import com.supplychainmanagement.repository.ShipmentRepository;
import com.supplychainmanagement.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Creating and changing shipments: a customer, at least one PACKED package of that customer, and
 * packages that move in and out without ever being deleted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShipmentPackageServiceImplTest {

    private static final Long CUSTOMER_ID = 3L;
    private static final Long OTHER_CUSTOMER_ID = 4L;
    private static final Long SHIPMENT_ID = 50L;

    @Mock
    private ShipmentRepository shipmentRepository;
    @Mock
    private ShipmentPackageRepository shipmentPackageRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ShipmentPackageServiceImpl service;

    /** Every package the "database" knows, with the customer its items belong to (null: empty). */
    private final Map<Long, ShipmentPackage> packages = new TreeMap<>();
    private final Map<Long, Long> customerOfPackage = new HashMap<>();

    private Customer customer;

    private record PackageCustomerRow(Long getShipmentPackageId, Long getCustomerId)
            implements ShipmentPackageRepository.PackageCustomer {
    }

    @BeforeEach
    void database() {
        customer = new Customer();
        customer.setId(CUSTOMER_ID);
        customer.setFirstName("Ada");
        customer.setLastName("Lovelace");
        when(userRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

        when(shipmentPackageRepository.findAllForUpdateByIdIn(anyCollection())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return packages.values().stream().filter(p -> ids.contains(p.getId())).toList();
        });
        when(shipmentPackageRepository.findCustomersByShipmentPackageIdIn(anyCollection())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return ids.stream()
                    .filter(customerOfPackage::containsKey)
                    .map(id -> (ShipmentPackageRepository.PackageCustomer) new PackageCustomerRow(id, customerOfPackage.get(id)))
                    .toList();
        });
        when(shipmentPackageRepository.findWithItemsByIdIn(anyCollection())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return packages.values().stream().filter(p -> ids.contains(p.getId())).toList();
        });
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(call -> {
            Shipment shipment = call.getArgument(0);
            shipment.setId(SHIPMENT_ID);
            return shipment;
        });
    }

    /** A package in the given status whose items belong to an order of the given customer. */
    private ShipmentPackage shipmentPackage(Long id, ShipmentPackageStatus status, Long customerId) {
        ShipmentPackage shipmentPackage = new ShipmentPackage();
        shipmentPackage.setId(id);
        shipmentPackage.setShipmentPackageStatus(status);
        shipmentPackage.setShipmentPackageType(ShipmentPackageType.CARTON);
        shipmentPackage.setPackageNumber("PKG-" + id);
        packages.put(id, shipmentPackage);
        if (customerId != null) {
            customerOfPackage.put(id, customerId);
        }
        return shipmentPackage;
    }

    /** A package item on an order line, with what the response reads from it. */
    private static PackageItem itemOfAnOrder() {
        Order order = new Order();
        order.setOrderNo(1042L);

        Product product = new Product();
        product.setSku(UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8"));

        OrderItem line = new OrderItem();
        line.setId(11L);
        line.setOrder(order);
        line.setProduct(product);
        line.setQuantity(2);

        PackageItem item = new PackageItem();
        item.setOrderItem(line);
        item.setQuantity(2);
        return item;
    }

    private ShipmentPackage packed(Long id) {
        return shipmentPackage(id, ShipmentPackageStatus.PACKED, CUSTOMER_ID);
    }

    private static CreateShipmentRequest create(Long customerId, Long... packageIds) {
        return new CreateShipmentRequest(customerId, List.of(packageIds), " Musterstr. 1, Berlin ", "DHL",
                LocalDate.of(2026, 10, 1));
    }

    private static ShipmentPackageIdsRequest ids(Long... ids) {
        return new ShipmentPackageIdsRequest(List.of(ids));
    }

    /** An existing shipment of the customer, locked and holding the given packages. */
    private Shipment existingShipment(ShipmentStatus status, ShipmentPackage... held) {
        Shipment shipment = new Shipment();
        shipment.setId(SHIPMENT_ID);
        shipment.setCustomer(customer);
        shipment.setStatus(status);
        shipment.setShippingAddress("Musterstr. 1");
        for (ShipmentPackage shipmentPackage : held) {
            shipment.addPackage(shipmentPackage);
        }
        when(shipmentRepository.findForUpdateById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        return shipment;
    }

    private static void assertStatus(Throwable thrown, HttpStatus status) {
        assertThat(thrown).isInstanceOfSatisfying(APIException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    // ------------------------------------------------------------------ create

    @Test
    void createsAShipmentWithThePackagesOfTheCustomer() {
        ShipmentPackage first = packed(5L);
        ShipmentPackage second = packed(7L);

        ShipmentResponse response = service.createShipment(create(CUSTOMER_ID, 7L, 5L));

        assertThat(first.getShipment()).isNotNull();
        assertThat(second.getShipment()).isSameAs(first.getShipment());
        Shipment shipment = first.getShipment();
        assertThat(shipment.getCustomer()).isSameAs(customer);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(shipment.getShippingAddress()).isEqualTo("Musterstr. 1, Berlin");

        assertThat(response.id()).isEqualTo(SHIPMENT_ID);
        assertThat(response.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(response.customerName()).isEqualTo("Ada Lovelace");
        assertThat(response.packages()).extracting(p -> p.id()).containsExactly(5L, 7L);
        // Empty contents in this test, so the gross weight is the packaging of both cartons.
        assertThat(response.weight()).isEqualByComparingTo(ShipmentPackageType.CARTON.getTareWeight().multiply(
                java.math.BigDecimal.valueOf(2)));
    }

    /** The way a package gets there in practice: completed through complete(), then shipped. */
    @Test
    void acceptsAPackageClosedThroughComplete() {
        ShipmentPackage shipmentPackage = shipmentPackage(5L, ShipmentPackageStatus.OPEN, CUSTOMER_ID);
        // complete() needs an item - never an empty package.
        shipmentPackage.addItem(itemOfAnOrder());
        shipmentPackage.complete();

        service.createShipment(create(CUSTOMER_ID, 5L));

        assertThat(shipmentPackage.getShipment()).isNotNull();
    }

    @Test
    void refusesAUserWhoIsNotACustomer() {
        Manager manager = new Manager();
        manager.setId(8L);
        when(userRepository.findById(8L)).thenReturn(Optional.of(manager));
        packed(5L);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.createShipment(create(8L, 5L)));

        assertStatus(thrown, HttpStatus.BAD_REQUEST);
        assertThat(thrown).hasMessageContaining("User 8 is not a customer");
        verify(shipmentRepository, never()).save(any());
    }

    @Test
    void answersAnUnknownCustomerWith404() {
        assertThatThrownBy(() -> service.createShipment(create(99L, 5L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void namesEveryUnknownPackage() {
        packed(5L);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.createShipment(create(CUSTOMER_ID, 5L, 6L, 8L)));

        assertStatus(thrown, HttpStatus.NOT_FOUND);
        assertThat(thrown).hasMessageContaining("[6, 8]");
    }

    /** Only PACKED packages travel - an OPEN one can still change. */
    @Test
    void refusesAPackageThatIsNotPacked() {
        packed(5L);
        shipmentPackage(6L, ShipmentPackageStatus.OPEN, CUSTOMER_ID);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.createShipment(create(CUSTOMER_ID, 5L, 6L)));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("ShipmentPackage 6 is OPEN");
        verify(shipmentRepository, never()).save(any());
    }

    @Test
    void refusesAPackageAlreadyInAnotherShipment() {
        ShipmentPackage taken = packed(5L);
        Shipment other = new Shipment();
        other.setId(40L);
        taken.setShipment(other);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.createShipment(create(CUSTOMER_ID, 5L)));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("already in Shipment 40");
    }

    /** Valid means: the package holds items of an order of this customer. */
    @Test
    void refusesAPackageOfAnotherCustomer() {
        packed(5L);
        shipmentPackage(6L, ShipmentPackageStatus.PACKED, OTHER_CUSTOMER_ID);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.createShipment(create(CUSTOMER_ID, 5L, 6L)));

        assertStatus(thrown, HttpStatus.BAD_REQUEST);
        assertThat(thrown).hasMessageContaining("ShipmentPackage 6 holds items of customer [4]");
        verify(shipmentRepository, never()).save(any());
    }

    /** An empty package goes to no customer - it cannot be valid for this one. */
    @Test
    void refusesAnEmptyPackage() {
        shipmentPackage(5L, ShipmentPackageStatus.PACKED, null);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.createShipment(create(CUSTOMER_ID, 5L)));

        assertStatus(thrown, HttpStatus.BAD_REQUEST);
        assertThat(thrown).hasMessageContaining("holds no items");
    }

    /** uk_shipment_package_number would fail the flush with a 500 - refused up front. */
    @Test
    void refusesTwoPackagesWithTheSameNumber() {
        packed(5L).setPackageNumber("PKG-SAME");
        packed(6L).setPackageNumber("PKG-SAME");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.createShipment(create(CUSTOMER_ID, 5L, 6L)));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("PKG-SAME");
    }

    /** The DTO rules again, for callers that do not come through the validated controller. */
    @Test
    void refusesARequestWithoutAddress() {
        packed(5L);

        assertThatThrownBy(() -> service.createShipment(new CreateShipmentRequest(CUSTOMER_ID, List.of(5L), " ", null, null)))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("shippingAddress is required");
    }

    // ------------------------------------------------------------------ add

    @Test
    void addsPackagesAndLeavesThoseAlreadyInIt() {
        ShipmentPackage held = packed(5L);
        Shipment shipment = existingShipment(ShipmentStatus.CREATED, held);
        ShipmentPackage added = packed(7L);

        service.addShipmentPackages(SHIPMENT_ID, ids(5L, 7L));

        assertThat(shipment.getPackages()).containsExactly(held, added);
        assertThat(added.getShipment()).isSameAs(shipment);
    }

    @Test
    void refusesToAddAPackageOfAnotherCustomer() {
        existingShipment(ShipmentStatus.CREATED, packed(5L));
        shipmentPackage(7L, ShipmentPackageStatus.PACKED, OTHER_CUSTOMER_ID);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.addShipmentPackages(SHIPMENT_ID, ids(7L)));

        assertStatus(thrown, HttpStatus.BAD_REQUEST);
    }

    /** Once handed on, a shipment keeps what it holds. */
    @Test
    void changesACreatedShipmentOnly() {
        existingShipment(ShipmentStatus.IN_TRANSIT, packed(5L));
        packed(7L);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.addShipmentPackages(SHIPMENT_ID, ids(7L)));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("only a CREATED shipment can be changed");
    }

    // ------------------------------------------------------------------ replace

    @Test
    void replacesThePackagesAndFreesTheOnesLeftOut() {
        ShipmentPackage leaving = packed(5L);
        ShipmentPackage staying = packed(6L);
        Shipment shipment = existingShipment(ShipmentStatus.CREATED, leaving, staying);
        ShipmentPackage arriving = packed(7L);

        service.replaceShipmentPackages(SHIPMENT_ID, ids(6L, 7L));

        assertThat(shipment.getPackages()).containsExactlyInAnyOrder(staying, arriving);
        assertThat(leaving.getShipment()).isNull();
        verify(shipmentPackageRepository, never()).delete(any());
    }

    /**
     * What leaves is written before anything arrives - a leaving and an arriving package with the
     * same number must never meet in uk_shipment_package_number.
     */
    @Test
    void flushesTheLeavingPackagesBeforeAnyArrives() {
        ShipmentPackage leaving = packed(5L);
        existingShipment(ShipmentStatus.CREATED, leaving);
        ShipmentPackage arriving = packed(7L);
        leaving.setPackageNumber("PKG-REPRINT");
        arriving.setPackageNumber("PKG-REPRINT");

        service.replaceShipmentPackages(SHIPMENT_ID, ids(7L));

        InOrder ordered = inOrder(shipmentPackageRepository);
        ordered.verify(shipmentPackageRepository).flush();
        ordered.verify(shipmentPackageRepository).findWithItemsByIdIn(anyCollection());
        assertThat(arriving.getShipment()).isNotNull();
    }

    @Test
    void refusesToEmptyAShipment() {
        existingShipment(ShipmentStatus.CREATED, packed(5L));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.replaceShipmentPackages(SHIPMENT_ID, ids()));

        assertStatus(thrown, HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------ remove

    @Test
    void removesAPackageWhichIsFreeAgain() {
        ShipmentPackage removed = packed(5L);
        ShipmentPackage kept = packed(6L);
        Shipment shipment = existingShipment(ShipmentStatus.CREATED, removed, kept);

        service.removeShipmentPackage(SHIPMENT_ID, 5L);

        assertThat(shipment.getPackages()).containsExactly(kept);
        assertThat(removed.getShipment()).isNull();
    }

    @Test
    void neverRemovesTheLastPackage() {
        existingShipment(ShipmentStatus.CREATED, packed(5L));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.removeShipmentPackage(SHIPMENT_ID, 5L));

        assertStatus(thrown, HttpStatus.BAD_REQUEST);
        assertThat(thrown).hasMessageContaining("last package");
    }

    @Test
    void refusesToRemoveAPackageThatIsNotInTheShipment() {
        existingShipment(ShipmentStatus.CREATED, packed(5L), packed(6L));
        packed(7L);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.removeShipmentPackage(SHIPMENT_ID, 7L));

        assertStatus(thrown, HttpStatus.BAD_REQUEST);
        assertThat(thrown).hasMessageContaining("is not in Shipment 50");
    }

    // ------------------------------------------------------------------ data and reading

    @Test
    void updatesTheShipmentData() {
        Shipment shipment = existingShipment(ShipmentStatus.CREATED, packed(5L));

        service.updateShipmentData(SHIPMENT_ID, new UpdateShipmentRequest("Neue Str. 2", "UPS", LocalDate.of(2026, 11, 2)));

        assertThat(shipment.getShippingAddress()).isEqualTo("Neue Str. 2");
        assertThat(shipment.getShippingMethod()).isEqualTo("UPS");
        assertThat(shipment.getRequestedDeliveryDate()).isEqualTo(LocalDate.of(2026, 11, 2));
    }

    /** Without a status the list covers every shipment. */
    @Test
    void listsAllShipmentsWithoutAStatus() {
        Shipment shipment = existingShipment(ShipmentStatus.CREATED, packed(6L), packed(5L));
        PageRequest pageable = PageRequest.of(0, 25);
        when(shipmentRepository.findAllWithCustomerBy(pageable)).thenReturn(new PageImpl<>(List.of(shipment), pageable, 1));
        when(shipmentRepository.findWithPackagesByIdIn(anyCollection())).thenReturn(List.of(shipment));

        var page = service.findShipments(null, pageable);

        assertThat(page.getContent()).singleElement()
                .satisfies(row -> assertThat(row.shipmentPackageIds()).containsExactly(5L, 6L));
        verify(shipmentRepository, never()).findAllWithCustomerByStatus(any(), any());
    }
}
