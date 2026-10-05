package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.RequestStatus;
import com.supplychainmanagement.repository.ComponentRepository;
import com.supplychainmanagement.repository.RequestComponentRepository;
import com.supplychainmanagement.repository.StockRepository;
import com.supplychainmanagement.repository.StorehouseRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.ComponentService;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.supplychainmanagement.support.TestData;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The whole inbound chain of a component request against the real database:
 * {@code OPEN → APPROVED → IN_TRANSIT → DELIVERED → IN_STOCK}, and what the last step books.
 * <p>
 * Why this needs a context: the goods receipt is the only place component stock grows, and every
 * unit test of it mocks {@link StockService} away - so nothing checked that
 * {@code POST /components/warehouse/{id}/in-stock/{storehouseId}} really writes
 * {@code stock.on_hand}. That write goes through a read-modify-write on a row keyed by
 * {@code (storehouse_id, sku)}, which either finds the row or inserts it; with a mock, both halves
 * are invisible. The status side cannot be seen either: {@code IN_STOCK} has to pass the CHECK
 * constraint on {@code request_components.request_status} (see {@code StatusCheckConstraintTest})
 * and the row has to survive a flush.
 * <p>
 * Needs a reachable database and rolls back. The entity manager is flushed and cleared before the
 * closing assertions, so they read what the database holds rather than the instances the service
 * just touched.
 */
@SpringBootTest
@Import(TestData.class)
@ActiveProfiles("test")
@Transactional
class ComponentRequestWorkflowTest {

    @Autowired
    private TestData testData;
    @Autowired
    private ComponentService componentService;
    @Autowired
    private RequestComponentRepository requestComponentRepository;
    @Autowired
    private ComponentRepository componentRepository;
    @Autowired
    private StorehouseRepository storehouseRepository;
    @Autowired
    private StockRepository stockRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    /**
     * Created once per test and remembered, so the storehouse and the SKU stay the same across the
     * several calls one test makes - a fresh row per call would compare stock of different articles.
     */
    private Component component;
    private Supplier supplier;
    private Storehouse storehouse;

    private Component component() {
        if (component == null) {
            component = testData.component();
        }
        return component;
    }

    private Supplier supplier() {
        if (supplier == null) {
            supplier = testData.supplier();
        }
        return supplier;
    }

    private Storehouse storehouse() {
        if (storehouse == null) {
            storehouse = testData.storehouse();
        }
        return storehouse;
    }

    /** A placed request in OPEN, the way requestComponents leaves it. */
    private RequestComponent placed(long qty) {
        RequestComponent request = new RequestComponent();
        request.setComponent(component());
        request.setSupplier(supplier());
        request.setQty(qty);
        request.setComment("workflow test");
        return requestComponentRepository.saveAndFlush(request);
    }

    /** What that SKU currently sits at in that storehouse - 0 when there is no row yet. */
    private int onHand(UUID sku, Long storehouseId) {
        return stockRepository.findByStorehouseIdAndSku(storehouseId, sku)
                .map(stock -> stock.getOnHand())
                .orElse(0);
    }

    /**
     * The chain end to end, with the quantity landing in stock. Each step is driven by the role that
     * owns it: the supplier answers three times, the warehouse books in once.
     */
    @Test
    void walksARequestFromOpenToInStockAndBooksTheQuantity() {
        Component component = component();
        Long storehouseId = storehouse().getId();
        Long supplierId = supplier().getId();
        int before = onHand(component.getSku(), storehouseId);
        RequestComponent request = placed(7L);

        assertThat(request.getRequestStatus()).isEqualTo(RequestStatus.OPEN);
        assertThat(componentService.approveRequest(request.getId(), supplierId).requestStatus())
                .isEqualTo(RequestStatus.APPROVED);
        assertThat(componentService.requestInTransit(request.getId(), supplierId).requestStatus())
                .isEqualTo(RequestStatus.IN_TRANSIT);
        assertThat(componentService.requestDelivered(request.getId(), supplierId).requestStatus())
                .isEqualTo(RequestStatus.DELIVERED);

        RequestComponentResponse received = componentService.receiveRequest(
                request.getId(), storehouseId, supplierId);

        assertThat(received.requestStatus()).isEqualTo(RequestStatus.IN_STOCK);
        assertThat(received.qty()).isEqualTo(7L);

        // Read back from the database, not from the instances the service holds.
        entityManager.flush();
        entityManager.clear();

        assertThat(onHand(component.getSku(), storehouseId)).isEqualTo(before + 7);
        assertThat(requestComponentRepository.findById(request.getId()))
                .get()
                .satisfies(stored -> assertThat(stored.getRequestStatus()).isEqualTo(RequestStatus.IN_STOCK));
    }

    /**
     * The quantity goes to the stock, never to {@code Component.qty} - that one is the bill of
     * materials, how many go into one product. Adding a delivery to it would silently rewrite the
     * recipe of every product using the part, and {@code assemble()} runs that recipe unattended.
     * Held against the database here, not only against a mock.
     */
    @Test
    void leavesTheBillOfMaterialsQuantityAlone() {
        Component component = component();
        Integer recipeQty = component.getQty();
        RequestComponent request = placed(5L);
        advanceToDelivered(request);

        componentService.receiveRequest(request.getId(), storehouse().getId(), supplier().getId());

        entityManager.flush();
        entityManager.clear();

        assertThat(componentRepository.findById(component.getId()))
                .get()
                .satisfies(stored -> assertThat(stored.getQty()).isEqualTo(recipeQty));
    }

    /**
     * Booking the same request in twice does not book the quantity twice: the second call finds
     * IN_STOCK and is a 409. Without that guard a double click would invent stock.
     */
    @Test
    void booksTheQuantityOnlyOnce() {
        Component component = component();
        Long storehouseId = storehouse().getId();
        RequestComponent request = placed(4L);
        advanceToDelivered(request);

        componentService.receiveRequest(request.getId(), storehouseId, supplier().getId());
        entityManager.flush();
        int afterFirst = onHand(component.getSku(), storehouseId);

        Throwable second = catchThrowable(() ->
                componentService.receiveRequest(request.getId(), storehouseId, supplier().getId()));

        assertThat(second).isInstanceOfSatisfying(APIException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(onHand(component.getSku(), storehouseId)).isEqualTo(afterFirst);
    }

    /**
     * The status does not move without the stock being booked: the storehouse is checked first, and
     * both halves share one transaction. A wrong path variable leaves the request on DELIVERED.
     */
    @Test
    void leavesTheStatusOnDeliveredWhenTheStorehouseIsUnknown() {
        RequestComponent request = placed(3L);
        advanceToDelivered(request);

        catchThrowable(() -> componentService.receiveRequest(request.getId(), -1L, supplier().getId()));

        assertThat(requestComponentRepository.findById(request.getId()))
                .get()
                .satisfies(stored -> assertThat(stored.getRequestStatus()).isEqualTo(RequestStatus.DELIVERED));
    }

    /**
     * {@code assignedBy} against the database: the column is written on every step and ends up
     * naming whoever moved the request last - here the warehouse, not the supplier who reported the
     * handover. Only a context test can see this: the column was added to a populated table by
     * {@code ddl-auto=update}, so it has to be there, nullable, and its foreign key has to hold.
     */
    @Test
    void recordsTheUserOfTheLastStep() {
        Long supplierId = supplier().getId();
        Long keeperId = someoneElseThan(supplierId);
        RequestComponent request = placed(2L);

        componentService.approveRequest(request.getId(), supplierId);
        componentService.requestInTransit(request.getId(), supplierId);
        componentService.requestDelivered(request.getId(), supplierId);

        entityManager.flush();
        entityManager.clear();
        assertThat(requestComponentRepository.findById(request.getId()))
                .get()
                .satisfies(afterSupplier -> assertThat(afterSupplier.getAssignedBy().getId())
                        .isEqualTo(supplierId));

        RequestComponentResponse received = componentService.receiveRequest(
                request.getId(), storehouse().getId(), keeperId);

        assertThat(received.assignedById()).isEqualTo(keeperId);
        assertThat(received.assignedByName()).isNotBlank();

        entityManager.flush();
        entityManager.clear();
        assertThat(requestComponentRepository.findById(request.getId()))
                .get()
                .satisfies(stored -> {
                    assertThat(stored.getAssignedBy().getId()).isEqualTo(keeperId);
                    // Hibernate stamps it; nothing writes it by hand.
                    assertThat(stored.getUpdated()).isNotNull();
                });
    }

    /**
     * The warehouse's work list finds the request that is waiting for it. The list is the reason the
     * receipt is reachable at all: it takes a request id, and until it existed only the supplier's
     * own list could be read.
     */
    @Test
    void showsADeliveredRequestInTheWorkList() {
        Long supplierId = supplier().getId();
        RequestComponent request = placed(6L);
        advanceToDelivered(request);
        entityManager.flush();

        var page = componentService.findRequests(RequestStatus.DELIVERED, PageRequest.of(0, 100,
                Sort.by(Sort.Direction.DESC, "id")));

        assertThat(page.getContent()).anySatisfy(row -> {
            assertThat(row.id()).isEqualTo(request.getId());
            assertThat(row.requestStatus()).isEqualTo(RequestStatus.DELIVERED);
            assertThat(row.assignedById()).isEqualTo(supplierId);
            // The entity graph fetched them, so reading them costs no query of its own.
            assertThat(row.componentId()).isNotNull();
            assertThat(row.componentName()).isNotNull();
        });
    }

    /**
     * Somebody who is not the supplier - the receipt is booked by the warehouse, and the test holds
     * that assignedBy ends up naming them and not the supplier who reported the handover.
     */
    private Long someoneElseThan(Long supplierId) {
        Long other = testData.customer().getId();
        if (other.equals(supplierId)) {
            throw new IllegalStateException("fixture handed out the same user twice");
        }
        return other;
    }

    /** The supplier's three steps, so a test about the receipt does not repeat them. */
    private void advanceToDelivered(RequestComponent request) {
        Long supplierId = supplier().getId();
        componentService.approveRequest(request.getId(), supplierId);
        componentService.requestInTransit(request.getId(), supplierId);
        componentService.requestDelivered(request.getId(), supplierId);
    }
}
