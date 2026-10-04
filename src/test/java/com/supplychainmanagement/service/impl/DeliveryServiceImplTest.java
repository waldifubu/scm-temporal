package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.DeliveryResponse;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.Role;
import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.Admin;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import com.supplychainmanagement.repository.ShipmentRepository;
import com.supplychainmanagement.service.RoleService;
import com.supplychainmanagement.service.OrderProgressService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The carrier's three steps. What they change on the shipment and its packages is decided here;
 * which of the orders really move is OrderProgressService's rule, so this test only checks that they
 * are handed over.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryServiceImplTest {

    private static final Long SHIPMENT_ID = 50L;
    private static final Long ORDER_ID = 1042L;

    @Mock
    private ShipmentRepository shipmentRepository;
    @Mock
    private ShipmentPackageRepository shipmentPackageRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderProgressService orderProgress;
    @Mock
    private RoleService roleService;

    @InjectMocks
    private DeliveryServiceImpl service;

    private static ShipmentPackage shipmentPackage(Long id, ShipmentPackageStatus status) {
        ShipmentPackage shipmentPackage = new ShipmentPackage();
        shipmentPackage.setId(id);
        shipmentPackage.setShipmentPackageStatus(status);
        shipmentPackage.setShipmentPackageType(ShipmentPackageType.CARTON);
        shipmentPackage.setPackageNumber("PKG-" + id);
        return shipmentPackage;
    }

    /** The distributor the shipment was handed over to - the one allowed to report on it. */
    private static Distributor assignedDistributor() {
        Distributor distributor = new Distributor();
        distributor.setId(DISTRIBUTOR_ID);
        distributor.setFirstName("Grace");
        distributor.setLastName("Hopper");
        return distributor;
    }

    /** The shipment as the locking read hands it back, with an order behind its packages. */
    private Shipment shipment(ShipmentStatus status, ShipmentPackage... packages) {
        Customer customer = new Customer();
        customer.setId(3L);
        customer.setFirstName("Ada");
        customer.setLastName("Lovelace");

        Shipment shipment = new Shipment();
        shipment.setId(SHIPMENT_ID);
        shipment.setCustomer(customer);
        shipment.setStatus(status);
        shipment.setShippingAddress("Musterstr. 1");
        shipment.setDistributor(assignedDistributor());
        for (ShipmentPackage shipmentPackage : packages) {
            shipment.addPackage(shipmentPackage);
        }

        Order order = new Order();
        order.setId(ORDER_ID);
        when(shipmentRepository.findForUpdateById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        when(orderRepository.findByShipmentId(SHIPMENT_ID)).thenReturn(List.of(order));
        return shipment;
    }

    private static void assertStatus(Throwable thrown, HttpStatus status) {
        assertThat(thrown).isInstanceOfSatisfying(APIException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    // ------------------------------------------------------------------ accept

    @Test
    void acceptTakesTheOrdersToReadyForDispatch() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCH_REQUESTED, shipmentPackage(5L, ShipmentPackageStatus.PACKED));

        DeliveryResponse response = service.acceptShipment(SHIPMENT_ID, 99L);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.ACCEPTED);
        assertThat(response.status()).isEqualTo(ShipmentStatus.ACCEPTED);
        assertThat(response.packageCount()).isEqualTo(1);
        // No target named: where the order really stands is worked out from its shipped quantities,
        // which is OrderProgressServiceImplTest's business.
        verify(orderProgress).recompute(List.of(ORDER_ID), 99L);
    }

    /**
     * Only from DISPATCH_REQUESTED, and that status comes from assignDistributor alone - so the
     * handover is a step that has to happen. A merely READY shipment used to be acceptable by any
     * carrier, which left every distributor's work list empty.
     */
    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"CREATED", "READY", "ACCEPTED", "IN_TRANSIT", "DELIVERED", "CANCELLED"})
    void acceptsAHandedOverShipmentOnly(ShipmentStatus status) {
        Shipment shipment = shipment(status, shipmentPackage(5L, ShipmentPackageStatus.PACKED));

        assertStatus(catchThrowable(() -> service.acceptShipment(SHIPMENT_ID, DISTRIBUTOR_ID)), HttpStatus.CONFLICT);

        assertThat(shipment.getStatus()).isEqualTo(status);
        verify(orderProgress, never()).recompute(any(), any());
    }

    // ------------------------------------------------------------------ whose shipment it is

    /** Another carrier does not report on somebody else's shipment. */
    @Test
    void refusesACarrierTheShipmentWasNotAssignedTo() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCH_REQUESTED, shipmentPackage(5L, ShipmentPackageStatus.PACKED));

        assertStatus(catchThrowable(() -> service.acceptShipment(SHIPMENT_ID, OTHER_DISTRIBUTOR_ID)), HttpStatus.FORBIDDEN);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.DISPATCH_REQUESTED);
    }

    /** ADMIN reports on any of them - the role that has to be able to correct things. */
    @Test
    void letsAnAdminReportOnAnyShipment() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCH_REQUESTED, shipmentPackage(5L, ShipmentPackageStatus.PACKED));
        when(roleService.isAdmin(1L)).thenReturn(true);

        service.acceptShipment(SHIPMENT_ID, 1L);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.ACCEPTED);
    }

    /**
     * Asked before the status, so a carrier poking at a shipment that is not theirs learns nothing
     * about where it stands.
     */
    @Test
    void doesNotTellAnotherCarrierWhatStatusItIsIn() {
        shipment(ShipmentStatus.CREATED, shipmentPackage(5L, ShipmentPackageStatus.PACKED));

        assertStatus(catchThrowable(() -> service.acceptShipment(SHIPMENT_ID, OTHER_DISTRIBUTOR_ID)), HttpStatus.FORBIDDEN);
    }

    /**
     * Not reachable through accept any more, which needs an assignment to get to
     * DISPATCH_REQUESTED - checked all the same, so the later steps do not depend on that chain.
     */
    @Test
    void refusesAShipmentWithoutADistributor() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCH_REQUESTED, shipmentPackage(5L, ShipmentPackageStatus.PACKED));
        shipment.setDistributor(null);

        Throwable thrown = catchThrowable(() -> service.acceptShipment(SHIPMENT_ID, DISTRIBUTOR_ID));

        assertStatus(thrown, HttpStatus.CONFLICT);
        assertThat(thrown).hasMessageContaining("has no distributor assigned");
    }

    /** Only the step onto the road dispatches the packages - accepting leaves them packed. */
    @Test
    void acceptLeavesThePackagesPacked() {
        ShipmentPackage shipmentPackage = shipmentPackage(5L, ShipmentPackageStatus.PACKED);
        shipment(ShipmentStatus.DISPATCH_REQUESTED, shipmentPackage);

        service.acceptShipment(SHIPMENT_ID, 99L);

        assertThat(shipmentPackage.getShipmentPackageStatus()).isEqualTo(ShipmentPackageStatus.PACKED);
    }

    // ------------------------------------------------------------------ in transit

    @Test
    void inTransitStampsTheShipmentDispatchesThePackagesAndMovesTheOrders() {
        ShipmentPackage first = shipmentPackage(5L, ShipmentPackageStatus.PACKED);
        ShipmentPackage second = shipmentPackage(6L, ShipmentPackageStatus.PACKED);
        Shipment shipment = shipment(ShipmentStatus.ACCEPTED, first, second);

        DeliveryResponse response = service.shipmentInTransit(SHIPMENT_ID, 99L);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(shipment.getShippedAt()).isNotNull();
        assertThat(shipment.getDeliveredAt()).isNull();
        assertThat(first.getShipmentPackageStatus()).isEqualTo(ShipmentPackageStatus.DISPATCHED);
        assertThat(second.getShipmentPackageStatus()).isEqualTo(ShipmentPackageStatus.DISPATCHED);
        verify(shipmentPackageRepository).saveAll(List.of(first, second));
        verify(orderProgress).recompute(List.of(ORDER_ID), 99L);
        assertThat(response.shippedAt()).isNotNull();
        assertThat(response.packageNumbers()).containsExactly("PKG-5", "PKG-6");
    }

    /** A package that already left - a repeat of the call - keeps what it has. */
    @Test
    void inTransitLeavesADispatchedPackageAlone() {
        ShipmentPackage alreadyGone = shipmentPackage(5L, ShipmentPackageStatus.DISPATCHED);
        shipment(ShipmentStatus.ACCEPTED, alreadyGone);

        service.shipmentInTransit(SHIPMENT_ID, 99L);

        assertThat(alreadyGone.getShipmentPackageStatus()).isEqualTo(ShipmentPackageStatus.DISPATCHED);
        verify(shipmentPackageRepository).saveAll(List.of());
    }

    @Test
    void goesOnTheRoadFromAcceptedOnly() {
        shipment(ShipmentStatus.DISPATCH_REQUESTED, shipmentPackage(5L, ShipmentPackageStatus.PACKED));

        assertStatus(catchThrowable(() -> service.shipmentInTransit(SHIPMENT_ID, 99L)), HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------ delivered

    @Test
    void deliveredStampsTheArrivalAndMovesTheOrders() {
        Shipment shipment = shipment(ShipmentStatus.IN_TRANSIT, shipmentPackage(5L, ShipmentPackageStatus.DISPATCHED));

        DeliveryResponse response = service.shipmentDelivered(SHIPMENT_ID, 99L);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(shipment.getDeliveredAt()).isNotNull();
        assertThat(response.deliveredAt()).isNotNull();
        // Not a fixed DELIVERED: an order whose other packages are still out only reaches
        // PARTIALLY_DELIVERED, and the quantities decide that - OrderProgressServiceImplTest.
        verify(orderProgress).recompute(List.of(ORDER_ID), 99L);
    }

    /** Only one step at a time: delivering a shipment that never left is a 409. */
    @Test
    void deliversAShipmentInTransitOnly() {
        shipment(ShipmentStatus.ACCEPTED, shipmentPackage(5L, ShipmentPackageStatus.PACKED));

        assertStatus(catchThrowable(() -> service.shipmentDelivered(SHIPMENT_ID, 99L)), HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------ the distributor's work list

    /** Who the shipments were handed over to - the same user the carrier steps act as. */
    private static final Long DISTRIBUTOR_ID = 99L;
    /** Somebody else's carrier, for the ownership checks. */
    private static final Long OTHER_DISTRIBUTOR_ID = 7L;
    private static final Pageable FIRST_PAGE = PageRequest.of(0, 25);

    /** A shipment as the page query returns it: its customer, but not yet its packages. */
    private static Shipment listRow(Long id) {
        Customer customer = new Customer();
        customer.setId(3L);
        customer.setFirstName("Ada");
        customer.setLastName("Lovelace");

        Shipment shipment = new Shipment();
        shipment.setId(id);
        shipment.setCustomer(customer);
        shipment.setStatus(ShipmentStatus.ACCEPTED);
        return shipment;
    }

    /**
     * Two queries, like every other shipment list: the page decides order and totals, the second
     * query adds the packages - a collection fetch in a paged query would page in memory.
     */
    @Test
    void listsTheShipmentsOfTheDistributorWithTheirPackages() {
        Shipment row = listRow(SHIPMENT_ID);
        Shipment withPackages = listRow(SHIPMENT_ID);
        withPackages.addPackage(shipmentPackage(5L, ShipmentPackageStatus.DISPATCHED));
        when(shipmentRepository.findAllWithCustomerByDistributorId(DISTRIBUTOR_ID, FIRST_PAGE))
                .thenReturn(new PageImpl<>(List.of(row), FIRST_PAGE, 1));
        when(shipmentRepository.findWithPackagesByIdIn(List.of(SHIPMENT_ID))).thenReturn(List.of(withPackages));

        Page<DeliveryResponse> page = service.findShipmentsForDistributor(DISTRIBUTOR_ID, null, FIRST_PAGE);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement().satisfies(delivery -> {
            assertThat(delivery.shipmentId()).isEqualTo(SHIPMENT_ID);
            assertThat(delivery.customerName()).isEqualTo("Ada Lovelace");
            assertThat(delivery.packageCount()).isEqualTo(1);
            assertThat(delivery.packageNumbers()).containsExactly("PKG-5");
        });
    }

    /** With a status the narrowing query is asked, not the whole list filtered afterwards. */
    @Test
    void asksTheNarrowedQueryWhenAStatusIsGiven() {
        when(shipmentRepository.findAllWithCustomerByDistributorIdAndStatus(
                DISTRIBUTOR_ID, ShipmentStatus.IN_TRANSIT, FIRST_PAGE)).thenReturn(Page.empty(FIRST_PAGE));

        service.findShipmentsForDistributor(DISTRIBUTOR_ID, ShipmentStatus.IN_TRANSIT, FIRST_PAGE);

        verify(shipmentRepository, never()).findAllWithCustomerByDistributorId(any(), any());
    }

    /** Nothing on the page, nothing to load packages for - the second query is not asked at all. */
    @Test
    void asksForNoPackagesWhenTheDistributorHasNoShipments() {
        when(shipmentRepository.findAllWithCustomerByDistributorId(DISTRIBUTOR_ID, FIRST_PAGE))
                .thenReturn(Page.empty(FIRST_PAGE));

        assertThat(service.findShipmentsForDistributor(DISTRIBUTOR_ID, null, FIRST_PAGE)).isEmpty();

        verify(shipmentRepository, never()).findWithPackagesByIdIn(any());
    }

    @Test
    void answersAnUnknownShipmentWith404() {
        when(shipmentRepository.findForUpdateById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptShipment(99L, 1L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
