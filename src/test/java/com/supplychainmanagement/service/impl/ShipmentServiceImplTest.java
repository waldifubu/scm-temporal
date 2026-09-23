package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.CancelShipmentRequest;
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
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.Manager;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.event.OrderStatusChangedEvent;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.service.OrderProgressService;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import com.supplychainmanagement.repository.ShipmentRepository;
import com.supplychainmanagement.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
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
class ShipmentServiceImplTest {

    private static final Long CUSTOMER_ID = 3L;
    private static final Long OTHER_CUSTOMER_ID = 4L;
    private static final Long SHIPMENT_ID = 50L;

    @Mock
    private ShipmentRepository shipmentRepository;
    @Mock
    private ShipmentPackageRepository shipmentPackageRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private OrderProgressService orderProgress;

    @InjectMocks
    private ShipmentServiceImpl service;

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
            shipmentPackage.getItems().add(itemOfCustomer(customerId));
        }
        return shipmentPackage;
    }

    /** One item on a line of an order of the given customer - where createShipment finds the customer. */
    private static PackageItem itemOfCustomer(Long customerId) {
        Customer owner = new Customer();
        owner.setId(customerId);
        PackageItem item = itemOfAnOrder();
        item.getOrderItem().getOrder().setCustomer(owner);
        return item;
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

    /** The customer is not part of the request - it comes from the packages. */
    private static CreateShipmentRequest create(Long... packageIds) {
        return new CreateShipmentRequest(List.of(packageIds), " Musterstr. 1, Berlin ", "DHL",
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

        ShipmentResponse response = service.createShipment(create(7L, 5L));

        assertThat(first.getShipment()).isNotNull();
        assertThat(second.getShipment()).isSameAs(first.getShipment());
        Shipment shipment = first.getShipment();
        assertThat(shipment.getCustomer()).isSameAs(customer);
        // The status defaults to CREATED in Shipment's @PrePersist, which a mocked save does not run -
        // see ShipmentPrePersistTest.
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

        service.createShipment(create(5L));

        assertThat(shipmentPackage.getShipment()).isNotNull();
    }

    @Test
    void refusesAUserWhoIsNotACustomer() {
        Manager manager = new Manager();
        manager.setId(8L);
        when(userRepository.findById(8L)).thenReturn(Optional.of(manager));
        shipmentPackage(5L, ShipmentPackageStatus.PACKED, 8L);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.createShipment(create(5L)));

        assertStatus(thrown, HttpStatus.BAD_REQUEST);
        assertThat(thrown).hasMessageContaining("User 8 is not a customer");
        verify(shipmentRepository, never()).save(any());
    }

    @Test
    void answersAnUnknownCustomerWith404() {
        shipmentPackage(5L, ShipmentPackageStatus.PACKED, 99L);

        assertThatThrownBy(() -> service.createShipment(create(5L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void namesEveryUnknownPackage() {
        packed(5L);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.createShipment(create(5L, 6L, 8L)));

        assertStatus(thrown, HttpStatus.NOT_FOUND);
        assertThat(thrown).hasMessageContaining("[6, 8]");
    }

    /** Only PACKED packages travel - an OPEN one can still change. */
    @Test
    void refusesAPackageThatIsNotPacked() {
        packed(5L);
        shipmentPackage(6L, ShipmentPackageStatus.OPEN, CUSTOMER_ID);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.createShipment(create(5L, 6L)));

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
                () -> service.createShipment(create(5L)));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("already in Shipment 40");
    }

    /** Valid means: the package holds items of an order of this customer. */
    @Test
    void refusesAPackageOfAnotherCustomer() {
        packed(5L);
        shipmentPackage(6L, ShipmentPackageStatus.PACKED, OTHER_CUSTOMER_ID);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.createShipment(create(5L, 6L)));

        assertStatus(thrown, HttpStatus.BAD_REQUEST);
        assertThat(thrown).hasMessageContaining("ShipmentPackage 6 holds items of customer [4]");
        verify(shipmentRepository, never()).save(any());
    }

    /** An empty package goes to no customer - it cannot be valid for this one. */
    @Test
    void refusesAnEmptyPackage() {
        shipmentPackage(5L, ShipmentPackageStatus.PACKED, null);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.createShipment(create(5L)));

        assertStatus(thrown, HttpStatus.BAD_REQUEST);
        assertThat(thrown).hasMessageContaining("holds no items");
    }

    /** uk_shipment_package_number would fail the flush with a 500 - refused up front. */
    @Test
    void refusesTwoPackagesWithTheSameNumber() {
        packed(5L).setPackageNumber("PKG-SAME");
        packed(6L).setPackageNumber("PKG-SAME");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.createShipment(create(5L, 6L)));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("PKG-SAME");
    }

    /** The address is optional when creating - a blank one is stored as null, not as "". */
    @Test
    void createsAShipmentWithoutAddress() {
        packed(5L);

        ShipmentResponse response = service.createShipment(new CreateShipmentRequest(List.of(5L), " ", null, null));

        assertThat(response.shippingAddress()).isNull();
    }

    /** The customer is read from the first package - an empty one is a 400, not a NoSuchElementException. */
    @Test
    void refusesAnEmptyFirstPackage() {
        shipmentPackage(5L, ShipmentPackageStatus.PACKED, null);
        packed(6L);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.createShipment(create(5L, 6L)));

        assertStatus(thrown, HttpStatus.BAD_REQUEST);
        assertThat(thrown).hasMessageContaining("ShipmentPackage 5 holds no items");
        verify(shipmentRepository, never()).save(any());
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

    /** Replacing is held to the same customer: a package of another customer never arrives. */
    @Test
    void refusesToReplaceWithAPackageOfAnotherCustomer() {
        ShipmentPackage held = packed(5L);
        Shipment shipment = existingShipment(ShipmentStatus.CREATED, held);
        shipmentPackage(7L, ShipmentPackageStatus.PACKED, OTHER_CUSTOMER_ID);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.replaceShipmentPackages(SHIPMENT_ID, ids(7L)));

        assertStatus(thrown, HttpStatus.BAD_REQUEST);
        assertThat(thrown).hasMessageContaining("ShipmentPackage 7 holds items of customer [4]");
        assertThat(shipment.getPackages()).containsExactly(held);
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

        service.updateShipmentData(SHIPMENT_ID, new UpdateShipmentRequest("Neue Str. 2", "UPS", LocalDate.of(2026, 11, 2), null));

        assertThat(shipment.getShippingAddress()).isEqualTo("Neue Str. 2");
        assertThat(shipment.getShippingMethod()).isEqualTo("UPS");
        assertThat(shipment.getRequestedDeliveryDate()).isEqualTo(LocalDate.of(2026, 11, 2));
    }

    // ------------------------------------------------------------------ distributor

    /** The shipment as findWithPackagesById answers it - the non-locking read. */
    private Shipment loadedShipment(ShipmentStatus status) {
        Shipment shipment = existingShipment(status, packed(5L));
        when(shipmentRepository.findWithPackagesById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        return shipment;
    }

    /** Assigned, and answered with id and name - never the Distributor entity with its password hash. */
    @Test
    void assignsADistributor() {
        Shipment shipment = loadedShipment(ShipmentStatus.READY);
        Distributor distributor = new Distributor();
        distributor.setId(20L);
        distributor.setFirstName("Grace");
        distributor.setLastName("Hopper");
        when(userRepository.findById(20L)).thenReturn(Optional.of(distributor));

        ShipmentResponse response = service.assignDistributor(SHIPMENT_ID, 20L);

        assertThat(shipment.getDistributor()).isSameAs(distributor);
        assertThat(response.distributorId()).isEqualTo(20L);
        assertThat(response.distributorName()).isEqualTo("Grace Hopper");
    }

    /** Any other user is a 400 - the cast before the check used to make it a ClassCastException (500). */
    @Test
    void refusesAUserWhoIsNotADistributor() {
        Shipment shipment = loadedShipment(ShipmentStatus.READY);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.assignDistributor(SHIPMENT_ID, CUSTOMER_ID));

        assertStatus(thrown, HttpStatus.BAD_REQUEST);
        assertThat(thrown).hasMessageContaining("User 3 is not a distributor");
        assertThat(shipment.getDistributor()).isNull();
    }

    @Test
    void answersAnUnknownDistributorWith404() {
        loadedShipment(ShipmentStatus.READY);

        assertThatThrownBy(() -> service.assignDistributor(SHIPMENT_ID, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** A shipment that is still being put together gets no distributor yet - READY first. */
    @Test
    void assignsNoDistributorToACreatedShipment() {
        loadedShipment(ShipmentStatus.CREATED);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.assignDistributor(SHIPMENT_ID, 20L));

        assertStatus(thrown, HttpStatus.CONFLICT);
    }

    /** Once in transit the distributor is fixed. */
    @Test
    void assignsADistributorBeforeTheShipmentIsAcceptedOnly() {
        loadedShipment(ShipmentStatus.IN_TRANSIT);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.assignDistributor(SHIPMENT_ID, 20L));

        assertStatus(thrown, HttpStatus.CONFLICT);
    }

    /** The single shipment is mapped in the service, with the package contents fetched in one query. */
    @Test
    void findsAShipmentAsResponse() {
        loadedShipment(ShipmentStatus.CREATED);

        ShipmentResponse response = service.findShipment(SHIPMENT_ID);

        assertThat(response.id()).isEqualTo(SHIPMENT_ID);
        assertThat(response.packages()).extracting(p -> p.id()).containsExactly(5L);
        assertThat(response.distributorId()).isNull();
        verify(shipmentPackageRepository).findWithItemsByIdIn(anyCollection());
    }

    @Test
    void answersAnUnknownShipmentWith404() {
        assertThatThrownBy(() -> service.findShipment(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ ready for dispatch

    /** An order line of the shipment, in the given fulfillment status. */
    private OrderItem lineInShipment(Long id, FulfillmentStatus status) {
        OrderItem line = new OrderItem();
        line.setId(id);
        line.setFulfillmentStatus(status);
        return line;
    }

    /**
     * With every package closed the shipment is READY, and the lines it carries - PACKED, so fully
     * packed - go to READY_FOR_DISPATCH.
     */
    @Test
    void readyTakesThePackedLinesToReadyForDispatch() {
        ShipmentPackage shipmentPackage = packed(5L);
        Shipment shipment = existingShipment(ShipmentStatus.CREATED, shipmentPackage);
        shipment.setShippingAddress("Musterstr. 1");
        when(shipmentRepository.findWithPackagesById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        OrderItem packedLine = lineInShipment(11L, FulfillmentStatus.PACKED);
        when(orderItemRepository.findByShipmentId(SHIPMENT_ID)).thenReturn(List.of(packedLine));

        service.checkShipmentReady(SHIPMENT_ID, 99L);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY);
        assertThat(packedLine.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.READY_FOR_DISPATCH);
        verify(orderItemRepository).saveAll(List.of(packedLine));
    }

    /**
     * The orders follow their lines. READY_FOR_DISPATCH is the warehouse reporting them ready for
     * the distributor, so it belongs to this step - which of them really move is OrderProgressService's
     * rule, checked in its own test.
     */
    @Test
    void readyTakesTheOrdersToReadyForDispatch() {
        Shipment shipment = existingShipment(ShipmentStatus.CREATED, packed(5L));
        shipment.setShippingAddress("Musterstr. 1");
        when(shipmentRepository.findWithPackagesById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        orderInShipment(OrderStatus.IN_FULFILLMENT);

        service.checkShipmentReady(SHIPMENT_ID, 99L);

        verify(orderProgress).advance(List.of(1042L), OrderStatus.READY_FOR_DISPATCH, 99L);
    }

    /** An open package stops the whole step - the orders stay where they are as well. */
    @Test
    void readyLeavesTheOrdersAloneWhenAPackageIsStillOpen() {
        ShipmentPackage open = shipmentPackage(5L, ShipmentPackageStatus.OPEN, CUSTOMER_ID);
        Shipment shipment = existingShipment(ShipmentStatus.CREATED, open);
        shipment.setShippingAddress("Musterstr. 1");
        when(shipmentRepository.findWithPackagesById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        assertStatus(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.checkShipmentReady(SHIPMENT_ID, 99L)), HttpStatus.CONFLICT);

        verify(orderProgress, never()).advance(any(), any(), any());
    }

    /** A line that is only PACKING has parts in another package - it is not on its way yet. */
    @Test
    void readyLeavesALineThatIsNotFullyPacked() {
        ShipmentPackage shipmentPackage = packed(5L);
        Shipment shipment = existingShipment(ShipmentStatus.CREATED, shipmentPackage);
        shipment.setShippingAddress("Musterstr. 1");
        when(shipmentRepository.findWithPackagesById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        OrderItem halfPacked = lineInShipment(11L, FulfillmentStatus.PACKING);
        when(orderItemRepository.findByShipmentId(SHIPMENT_ID)).thenReturn(List.of(halfPacked));

        service.checkShipmentReady(SHIPMENT_ID, 99L);

        assertThat(halfPacked.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PACKING);
        verify(orderItemRepository).saveAll(List.of());
    }

    /** An open package stops the whole thing - and no line is touched. */
    @Test
    void readyRefusesAnOpenPackageAndTouchesNoLine() {
        ShipmentPackage open = shipmentPackage(5L, ShipmentPackageStatus.OPEN, CUSTOMER_ID);
        Shipment shipment = existingShipment(ShipmentStatus.CREATED, open);
        shipment.setShippingAddress("Musterstr. 1");
        when(shipmentRepository.findWithPackagesById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        assertStatus(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.checkShipmentReady(SHIPMENT_ID, 99L)), HttpStatus.CONFLICT);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        verify(orderItemRepository, never()).saveAll(any());
    }

    /** Reported ready once, and only from CREATED - a shipment on its way is not re-reported. */
    @Test
    void reportsACreatedShipmentReadyOnly() {
        Shipment shipment = existingShipment(ShipmentStatus.READY, packed(5L));
        shipment.setShippingAddress("Musterstr. 1");
        OrderItem packedLine = lineInShipment(11L, FulfillmentStatus.PACKED);
        when(orderItemRepository.findByShipmentId(SHIPMENT_ID)).thenReturn(List.of(packedLine));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.checkShipmentReady(SHIPMENT_ID, 99L));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("only a CREATED shipment can be reported ready");
        assertThat(packedLine.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PACKED);
        verify(orderItemRepository, never()).saveAll(any());
    }

    /** allMatch says true for nothing at all - a shipment without packages is never ready. */
    @Test
    void refusesToReportAnEmptyShipmentReady() {
        Shipment shipment = existingShipment(ShipmentStatus.CREATED);
        shipment.setShippingAddress("Musterstr. 1");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.checkShipmentReady(SHIPMENT_ID, 99L));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("holds no packages");
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CREATED);
    }

    /** Without an address there is nowhere to deliver - and no line is moved. */
    @Test
    void refusesToReportAShipmentWithoutAnAddressReady() {
        Shipment shipment = existingShipment(ShipmentStatus.CREATED, packed(5L));
        shipment.setShippingAddress(null);

        assertStatus(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.checkShipmentReady(SHIPMENT_ID, 99L)), HttpStatus.CONFLICT);
        verify(orderItemRepository, never()).saveAll(any());
    }

    /**
     * Its packages are fixed from READY on: nothing goes in and nothing comes out, whatever the
     * carrier has already reported. Only the shipment's own data still changes - updateShipmentData
     * asks for no status.
     */
    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"READY", "DISPATCH_REQUESTED", "ACCEPTED", "IN_TRANSIT", "DELIVERED"})
    void keepsItsPackagesOnceItIsOnItsWay(ShipmentStatus status) {
        Shipment shipment = existingShipment(status, packed(5L), packed(6L));
        packed(7L);

        assertStatus(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.addShipmentPackages(SHIPMENT_ID, ids(7L))), HttpStatus.CONFLICT);
        assertStatus(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.replaceShipmentPackages(SHIPMENT_ID, ids(5L))), HttpStatus.CONFLICT);
        assertStatus(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.removeShipmentPackage(SHIPMENT_ID, 5L)), HttpStatus.CONFLICT);

        assertThat(shipment.getPackages()).hasSize(2);
    }

    /** Address and the rest stay correctable while the shipment is still in hand. */
    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"CREATED", "READY", "DISPATCH_REQUESTED", "ACCEPTED"})
    void changesTheShipmentDataUpToAccepted(ShipmentStatus status) {
        Shipment shipment = existingShipment(status, packed(5L));

        service.updateShipmentData(SHIPMENT_ID, new UpdateShipmentRequest("Neue Str. 2", "UPS", null, null));

        assertThat(shipment.getShippingAddress()).isEqualTo("Neue Str. 2");
        assertThat(shipment.getShippingMethod()).isEqualTo("UPS");
    }

    /** Once it is on the road the papers are out of the house - and a cancelled one is not edited. */
    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"IN_TRANSIT", "DELIVERED", "CANCELLED"})
    void keepsTheShipmentDataOnceItIsOnTheRoad(ShipmentStatus status) {
        Shipment shipment = existingShipment(status, packed(5L));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.updateShipmentData(SHIPMENT_ID, new UpdateShipmentRequest("Neue Str. 2", null, null, null)));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("its data can only be changed up to ACCEPTED");
        assertThat(shipment.getShippingAddress()).isEqualTo("Musterstr. 1");
    }

    // ------------------------------------------------------------------ cancel

    /**
     * Calling a shipment off undoes what it had set in motion: the packages are loose again and still
     * PACKED, the lines are back to PACKED and the order to IN_FULFILLMENT - with an audit row.
     */
    @Test
    void cancelHandsBackThePackagesAndTakesTheLinesAndOrderBack() {
        ShipmentPackage shipmentPackage = packed(5L);
        Shipment shipment = existingShipment(ShipmentStatus.READY, shipmentPackage);
        OrderItem line = lineInShipment(11L, FulfillmentStatus.READY_FOR_DISPATCH);
        when(orderItemRepository.findByShipmentId(SHIPMENT_ID)).thenReturn(List.of(line));
        orderInShipment(OrderStatus.READY_FOR_DISPATCH);

        service.cancelShipment(SHIPMENT_ID, new CancelShipmentRequest(" no truck today "), 99L);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(shipment.getComment()).isEqualTo("no truck today");
        assertThat(shipment.getPackages()).isEmpty();
        assertThat(shipmentPackage.getShipment()).isNull();
        assertThat(shipmentPackage.getShipmentPackageStatus()).isEqualTo(ShipmentPackageStatus.PACKED);
        assertThat(line.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PACKED);
        // Whether the order really goes back is decided on its lines - OrderProgressServiceImplTest.
        verify(orderProgress).takeBackFromDispatch(List.of(1042L), 99L);
    }

    /** Up to the handover only - once it rolls, a cancellation would be a return. */
    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"IN_TRANSIT", "DELIVERED", "CANCELLED"})
    void cancelsAShipmentUpToAcceptedOnly(ShipmentStatus status) {
        ShipmentPackage shipmentPackage = packed(5L);
        Shipment shipment = existingShipment(status, shipmentPackage);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.cancelShipment(SHIPMENT_ID, new CancelShipmentRequest("too late"), 99L));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("it can only be cancelled up to ACCEPTED");
        verify(orderProgress, never()).takeBackFromDispatch(any(), any());
        assertThat(shipment.getPackages()).containsExactly(shipmentPackage);
        assertThat(shipmentPackage.getShipment()).isSameAs(shipment);
    }

    /** Every cancellation says why - it is the only record of it. */
    @Test
    void cancelNeedsAReason() {
        Shipment shipment = existingShipment(ShipmentStatus.READY, packed(5L));

        assertStatus(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.cancelShipment(SHIPMENT_ID, new CancelShipmentRequest("  "), 99L)), HttpStatus.BAD_REQUEST);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY);
    }

    /** A shipment called off before it was ever reported ready touches no line and no order. */
    @Test
    void cancelOfACreatedShipmentMovesNothingBack() {
        ShipmentPackage shipmentPackage = packed(5L);
        Shipment shipment = existingShipment(ShipmentStatus.CREATED, shipmentPackage);
        OrderItem line = lineInShipment(11L, FulfillmentStatus.PACKED);
        when(orderItemRepository.findByShipmentId(SHIPMENT_ID)).thenReturn(List.of(line));
        Order order = orderInShipment(OrderStatus.IN_FULFILLMENT);

        service.cancelShipment(SHIPMENT_ID, new CancelShipmentRequest("repacking"), 99L);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(shipmentPackage.getShipment()).isNull();
        assertThat(line.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PACKED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_FULFILLMENT);
        verify(orderProgress).takeBackFromDispatch(List.of(1042L), 99L);
    }

    /** An order the shipment carries, in the given status. */
    private Order orderInShipment(OrderStatus status) {
        Order order = new Order();
        order.setId(1042L);
        order.setStatus(status);
        when(orderRepository.findByShipmentId(SHIPMENT_ID)).thenReturn(List.of(order));
        return order;
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
