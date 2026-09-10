package com.supplychainmanagement.repository;

import com.supplychainmanagement.model.enums.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

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
}
