package com.supplychainmanagement.repository;

import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Runs every hand-written {@code @Query} in the project once.
 * <p>
 * Bootstrap parses the JPQL, so a syntax error already fails the context - but it does <em>not</em>
 * check that the named parameters in the query have a matching {@code @Param}. That mismatch only
 * surfaces the first time the query executes, and it did:
 * {@code findEligibleBySku} asked for {@code :quantity} while its parameter was named {@code qty},
 * which broke availability checking and therefore reserving, and stayed hidden because every unit
 * test mocks the repositories away.
 * <p>
 * These are reads with arguments that match nothing on purpose. What is asserted is that the query
 * runs at all - the result is beside the point, so the test does not depend on the data in the
 * database. It does depend on the database being reachable, like {@link
 * com.supplychainmanagement.ApplicationTests}.
 */
@SpringBootTest
@Transactional
class CustomQueryExecutionTest {

    /** Belongs to nothing, which is what makes the assertions independent of the actual data. */
    private static final UUID UNKNOWN_SKU = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final Long UNKNOWN_ID = -1L;

    @Autowired
    private StockRepository stockRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ShipmentPackageRepository shipmentPackageRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private PackageItemRepository packageItemRepository;
    @Autowired
    private ShipmentRepository shipmentRepository;
    @Autowired
    private OrderRepository orderRepository;

    /** The one that broke: two named parameters, and the second was bound under a different name. */
    @Test
    void findEligibleBySkuBindsBothParameters() {
        assertThat(stockRepository.findEligibleBySku(UNKNOWN_SKU, 1)).isEmpty();
    }

    @Test
    void findBySkuForUpdateRuns() {
        assertThat(stockRepository.findBySkuForUpdate(UNKNOWN_SKU)).isEmpty();
    }

    @Test
    void findAvailableBySkuOrderByUpdatedAtAscRuns() {
        assertThat(stockRepository.findAvailableBySkuOrderByUpdatedAtAsc(UNKNOWN_SKU)).isEmpty();
    }

    /** COALESCE over an empty match has to come back as 0, not as null into an int. */
    @Test
    void sumQuantityByOrderItemIdReturnsZeroForNoPackages() {
        assertThat(shipmentPackageRepository.sumQuantityByOrderItemId(UNKNOWN_ID)).isZero();
    }

    /**
     * The locking read packing takes on an order line. Worth executing rather than only parsing:
     * the {@code FOR UPDATE} clause is added by the dialect, not by the JPQL, so a dialect that
     * cannot express it fails here and nowhere else.
     */
    @Test
    void findForUpdateByIdRuns() {
        assertThat(orderItemRepository.findForUpdateById(UNKNOWN_ID)).isEmpty();
    }

    /**
     * The order line projection with its count query, sorted by a column of the line itself - the
     * default sort of the endpoint.
     */
    @Test
    void findAllByFulfillmentStatusRunsWithItsCountQuery() {
        var page = orderItemRepository.findAllByFulfillmentStatus(
                FulfillmentStatus.PICKED,
                PageRequest.of(0, 5, Sort.by(Sort.Direction.ASC, "updatedAt")));

        assertThat(page).isNotNull();
        assertThat(page.getTotalElements()).isNotNegative();
    }

    /**
     * The reservation is left-joined, so a line without one must still be listed. The count query
     * carries no reservation join - if the page query ever turned into an inner join, it would deliver
     * fewer rows than the count promises. WAITING lines hold no reservation, which makes them the
     * status to look at. Data-independent: with no WAITING line at all both sides are 0.
     */
    @Test
    void findAllByFulfillmentStatusKeepsLinesWithoutAReservation() {
        var page = orderItemRepository.findAllByFulfillmentStatus(
                FulfillmentStatus.WAITING, PageRequest.of(0, 5));

        assertThat((long) page.getNumberOfElements()).isEqualTo(Math.min(page.getTotalElements(), 5));
    }

    /**
     * The projection: eleven constructor arguments across five joined entities, plus a count query
     * repeating the joins. Executed rather than just parsed, and sorted by a real field so the
     * Pageable's order clause is appended to the JPQL as well.
     */
    @Test
    void findPickingOrdersRunsWithItsCountQuery() {
        var page = reservationRepository.findPickingOrders(
                ReservationStatus.RELEASED,
                PageRequest.of(0, 5, Sort.by(Sort.Direction.ASC, "expiresAt")));

        assertThat(page).isNotNull();
        assertThat(page.getTotalElements()).isNotNegative();
    }

    /**
     * A sort over a column the projection only reaches through a join is not resolvable, and fails
     * at execution rather than at bootstrap. Pinned here so the limitation stays visible: whoever
     * lets the frontend sort the picking list by product name will land on this.
     */
    @Test
    void findPickingOrdersCannotSortOverAJoinedColumn() {
        assertThatCode(() -> reservationRepository.findPickingOrders(
                ReservationStatus.ACTIVE,
                PageRequest.of(0, 5, Sort.by(Sort.Direction.ASC, "productName"))).getContent())
                .isInstanceOf(Exception.class);
    }

    /** The locking read on a package, taken before its contents are changed. */
    @Test
    void findForUpdateByIdOfAPackageRuns() {
        assertThat(shipmentPackageRepository.findForUpdateById(UNKNOWN_ID)).isEmpty();
    }

    /** The locking read on package items, with IN and ORDER BY in the same statement as FOR UPDATE. */
    @Test
    void findAllForUpdateByIdInRuns() {
        assertThat(packageItemRepository.findAllForUpdateByIdIn(List.of(UNKNOWN_ID, -2L))).isEmpty();
    }

    /** The package list, paged and sorted by a field of the package. */
    @Test
    void findAllByShipmentPackageStatusRuns() {
        var page = shipmentPackageRepository.findAllByShipmentPackageStatus(
                ShipmentPackageStatus.OPEN, PageRequest.of(0, 5, Sort.by(Sort.Direction.ASC, "packageNumber")));

        assertThat(page.getTotalElements()).isNotNegative();
    }

    /** The entity graph with its nested paths only resolves when the query runs. */
    @Test
    void findWithItemsByIdInRuns() {
        assertThat(shipmentPackageRepository.findWithItemsByIdIn(List.of(UNKNOWN_ID))).isEmpty();
    }

    /** All package items with order line and product fetched, paged in SQL and sorted by an own field. */
    @Test
    void findAllWithProductByRuns() {
        var page = packageItemRepository.findAllWithProductBy(
                PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "created")));

        assertThat(page.getTotalElements()).isNotNegative();
    }

    /**
     * The loose items: every row the query returns really has no package - checked against the data,
     * so it also holds while there are loose items in the database.
     */
    @Test
    void findAllWithProductByShipmentPackageIsNullReturnsOnlyLooseItems() {
        var page = packageItemRepository.findAllWithProductByShipmentPackageIsNull(PageRequest.of(0, 50));

        assertThat(page.getContent()).allSatisfy(item -> assertThat(item.getShipmentPackage()).isNull());
    }

    /** The list filter with its package number part - Containing becomes a LIKE with wildcards. */
    @Test
    void findAllByShipmentPackageStatusAndPackageNumberContainingRuns() {
        var page = shipmentPackageRepository.findAllByShipmentPackageStatusAndPackageNumberContaining(
                ShipmentPackageStatus.OPEN, "PKG-", PageRequest.of(0, 5, Sort.by(Sort.Direction.ASC, "id")));

        assertThat(page.getContent()).allSatisfy(shipmentPackage ->
                assertThat(shipmentPackage.getPackageNumber()).contains("PKG-"));
    }

    /** The two detail finders with their entity graphs. */
    @Test
    void detailFindersRun() {
        assertThat(packageItemRepository.findWithProductById(UNKNOWN_ID)).isEmpty();
        assertThat(shipmentPackageRepository.findWithItemsById(UNKNOWN_ID)).isEmpty();
    }

    /** The id lookup the siblings are computed from, with its interface projection. */
    @Test
    void findItemIdsByOrderItemIdInRuns() {
        assertThat(packageItemRepository.findItemIdsByOrderItemIdIn(List.of(UNKNOWN_ID))).isEmpty();
    }

    // ------------------------------------------------------------------ reservations

    /**
     * A reservation's order is reached through its order line - the derived path orderItem.order.id
     * has to resolve, and the entity graphs with the line along with it.
     */
    @Test
    void reservationsResolveTheirOrderThroughTheLine() {
        assertThat(reservationRepository.findByOrderItemOrderIdAndStatus(UNKNOWN_ID, ReservationStatus.ACTIVE)).isEmpty();
        assertThat(reservationRepository.findByIdAndStatus(UNKNOWN_ID, ReservationStatus.ACTIVE)).isEmpty();
        assertThat(reservationRepository.findByStatusAndExpiresAtBefore(ReservationStatus.ACTIVE,
                java.time.LocalDateTime.of(2000, 1, 1, 0, 0))).isEmpty();
    }

    // ------------------------------------------------------------------ shipments

    /** The locking reads a shipment change takes: the shipment, then its packages in id order. */
    @Test
    void shipmentLockingReadsRun() {
        assertThat(shipmentRepository.findForUpdateById(UNKNOWN_ID)).isEmpty();
        assertThat(shipmentPackageRepository.findAllForUpdateByIdIn(List.of(UNKNOWN_ID, -2L))).isEmpty();
    }

    /** The customer behind a package, through items, order lines and orders - an interface projection. */
    @Test
    void findCustomersByShipmentPackageIdInRuns() {
        assertThat(shipmentPackageRepository.findCustomersByShipmentPackageIdIn(List.of(UNKNOWN_ID))).isEmpty();
    }

    /** The shipment list: paged with the customer fetched, with and without status. */
    @Test
    void shipmentPagesRun() {
        var pageable = PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt"));

        assertThat(shipmentRepository.findAllWithCustomerBy(pageable).getTotalElements()).isNotNegative();
        assertThat(shipmentRepository.findAllWithCustomerByDistributorId(UNKNOWN_ID, pageable).getTotalElements())
                .isZero();
        assertThat(shipmentRepository
                .findAllWithCustomerByDistributorIdAndStatus(UNKNOWN_ID, ShipmentStatus.CREATED, pageable)
                .getTotalElements()).isZero();
        assertThat(shipmentRepository.findAllWithCustomerByStatus(ShipmentStatus.CREATED, pageable).getTotalElements())
                .isNotNegative();
    }

    /** The order lines a shipment carries - what checkShipmentReady moves on. */
    @Test
    void findOrderItemsByShipmentIdRuns() {
        assertThat(orderItemRepository.findByShipmentId(UNKNOWN_ID)).isEmpty();
    }

    /** The orders behind a shipment, reached over packages, items and order lines. */
    @Test
    void findByShipmentIdRuns() {
        assertThat(orderRepository.findByShipmentId(UNKNOWN_ID)).isEmpty();
    }

    /** The entity graphs with the packages collection only resolve when the query runs. */
    @Test
    void shipmentPackageGraphsRun() {
        assertThat(shipmentRepository.findWithPackagesByIdIn(List.of(UNKNOWN_ID))).isEmpty();
        assertThat(shipmentRepository.findWithPackagesById(UNKNOWN_ID)).isEmpty();
    }
}
