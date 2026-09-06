package com.supplychainmanagement.repository;

import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.model.enums.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * The picking list, as a projection rather than as entities: one query for the page, nothing
     * lazy left to resolve while the response is written. Handing out Reservations instead meant a
     * query per row and per association, and the cycle
     * OrderItem -> Order -> orderItems on top of it.
     * <p>
     * The count query repeats the joins on purpose - they are inner joins and can drop rows, so a
     * plain {@code count(r)} would report a larger total than the page query can deliver.
     */
    @Query(value = """
            select new com.supplychainmanagement.dto.picking.PickingOrderDto(
                r.id, o.orderNo, oi.id, p.articleNo, p.name, r.sku, r.quantity,
                s.id, s.name, oi.fulfillmentStatus, r.expiresAt)
            from Reservation r
              join r.orderItem oi
              join oi.order o
              join oi.product p
              join r.storehouse s
            where r.status = :status
            """,
            countQuery = """
                    select count(r)
                    from Reservation r
                      join r.orderItem oi
                      join oi.order o
                      join oi.product p
                      join r.storehouse s
                    where r.status = :status
                    """)
    Page<PickingOrderDto> findPickingOrders(@Param("status") ReservationStatus status, Pageable pageable);

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
