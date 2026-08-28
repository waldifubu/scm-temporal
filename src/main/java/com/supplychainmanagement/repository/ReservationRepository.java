package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.model.enums.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    @EntityGraph(attributePaths = "storehouse")
    Optional<Reservation> findByOrderIdAndSkuAndStorehouseIdAndStatus(
            String orderId,
            UUID sku,
            Long storehouseId,
            ReservationStatus status);

    default Optional<Reservation> findActive(
            String orderId,
            UUID sku,
            Long storehouseId) {

        return findByOrderIdAndSkuAndStorehouseIdAndStatus(
                orderId,
                sku,
                storehouseId,
                ReservationStatus.ACTIVE);
    }

    default List<Reservation> findActive(String orderId) {
        return findByOrderIdAndStatus(
                orderId,
                ReservationStatus.ACTIVE);
    }

    @EntityGraph(attributePaths = "storehouse")
    List<Reservation> findByOrderIdAndStatus(
            String orderId,
            ReservationStatus status);

    @EntityGraph(attributePaths = "storehouse")
    List<Reservation> findAllActiveReservationsByStatusOrderByExpiresAt(ReservationStatus status);

    /**
     * Paged counterpart of the method above. The ordering is left to the {@link Pageable} instead of
     * being baked into the method name: a static OrderBy would be applied on top of the sort the
     * caller asked for, which is not what a sortable list view wants.
     */
    @EntityGraph(attributePaths = "storehouse")
    Page<Reservation> findAllByStatus(ReservationStatus status, Pageable pageable);

    /**
     * Looks one reservation up by its own id and requires it to be in the given status. The status
     * is matched by equality - {@code Contains} would translate to a LIKE, which is a String
     * operation and has no meaning for an enum column.
     * <p>
     * Keyed by id rather than by orderId: an order holds one reservation per SKU, so an Optional
     * over orderId would break with a NonUniqueResultException as soon as the order has a second
     * line. {@link #findByOrderIdAndStatus} is the one to use per order.
     */
    @EntityGraph(attributePaths = "storehouse")
    Optional<Reservation> findByIdAndStatus(Long id, ReservationStatus status);
}
