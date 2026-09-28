package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.CancelShipmentRequest;
import com.supplychainmanagement.dto.shipping.DeliveryResponse;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import com.supplychainmanagement.repository.ShipmentRepository;
import com.supplychainmanagement.service.OrderProgressService;
import com.supplychainmanagement.service.ShipmentService;
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
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

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
    private ShipmentService shipmentService;

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
        Shipment shipment = shipment(ShipmentStatus.READY, shipmentPackage(5L, ShipmentPackageStatus.PACKED));

        DeliveryResponse response = service.acceptShipment(SHIPMENT_ID, 99L);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.ACCEPTED);
        assertThat(response.status()).isEqualTo(ShipmentStatus.ACCEPTED);
        assertThat(response.packageCount()).isEqualTo(1);
        // No target named: where the order really stands is worked out from its shipped quantities,
        // which is OrderProgressServiceImplTest's business.
        verify(orderProgress).recompute(List.of(ORDER_ID), 99L);
    }

    /** A shipment still being put together is not accepted - READY or DISPATCH_REQUESTED first. */
    @Test
    void acceptsAReadyShipmentOnly() {
        Shipment shipment = shipment(ShipmentStatus.CREATED, shipmentPackage(5L, ShipmentPackageStatus.PACKED));

        assertStatus(catchThrowable(() -> service.acceptShipment(SHIPMENT_ID, 99L)), HttpStatus.CONFLICT);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        verify(orderProgress, never()).recompute(any(), any());
    }

    /** Only the step onto the road dispatches the packages - accepting leaves them packed. */
    @Test
    void acceptLeavesThePackagesPacked() {
        ShipmentPackage shipmentPackage = shipmentPackage(5L, ShipmentPackageStatus.PACKED);
        shipment(ShipmentStatus.READY, shipmentPackage);

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
        shipment(ShipmentStatus.READY, shipmentPackage(5L, ShipmentPackageStatus.PACKED));

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

    private static final Long DISTRIBUTOR_ID = 7L;
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

    // ------------------------------------------------------------------ handing the shipment back

    /**
     * The winding back itself belongs to ShipmentService - which statuses may still be called off,
     * the packages going loose, the lines and orders a step back - and its own test covers it. What
     * is checked here is that the carrier call ends up there and answers with the carrier view.
     */
    @Test
    void cancelGoesThroughTheShipmentServiceAndAnswersWithTheCarrierView() {
        CancelShipmentRequest request = new CancelShipmentRequest("truck broke down");
        Shipment cancelled = shipment(ShipmentStatus.ACCEPTED);
        cancelled.setStatus(ShipmentStatus.CANCELLED);
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(cancelled));

        DeliveryResponse response = service.cancelShipment(SHIPMENT_ID, request, 99L);

        verify(shipmentService).cancelShipment(SHIPMENT_ID, request, 99L);
        assertThat(response.status()).isEqualTo(ShipmentStatus.CANCELLED);
        // The packages left the shipment with the cancellation, so the answer names none.
        assertThat(response.packageCount()).isZero();
        assertThat(response.packageNumbers()).isEmpty();
    }

    /** The rules stay in one place: nothing about statuses, packages or orders is decided here. */
    @Test
    void cancelDecidesNothingItself() {
        Shipment cancelled = shipment(ShipmentStatus.ACCEPTED);
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(cancelled));

        service.cancelShipment(SHIPMENT_ID, new CancelShipmentRequest("no driver"), 99L);

        verify(orderProgress, never()).recompute(any(), any());
        verify(shipmentPackageRepository, never()).saveAll(any());
    }

    @Test
    void answersAnUnknownShipmentWith404() {
        when(shipmentRepository.findForUpdateById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptShipment(99L, 1L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
