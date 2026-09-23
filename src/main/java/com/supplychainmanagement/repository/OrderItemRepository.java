package com.supplychainmanagement.repository;

import com.supplychainmanagement.dto.order.OrderItemListDto;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /**
     * The order lines a shipment carries, each once - reached over its packages and their items,
     * since a line has no reference to a package. What {@code checkShipmentReady} takes to
     * READY_FOR_DISPATCH.
     */
    @Query("""
            select distinct oi
            from ShipmentPackage sp
              join sp.items pi
              join pi.orderItem oi
            where sp.shipment.id = :shipmentId
            """)
    List<OrderItem> findByShipmentId(@Param("shipmentId") Long shipmentId);
    @EntityGraph(attributePaths = {"order", "product"})
    Optional<OrderItem> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"order", "product"})
    List<OrderItem> findAllBy();


    /**
     * Locks the line for the duration of the transaction. Packing reads how much of it is already
     * packed and then writes one more package - without the lock two concurrent calls read the same
     * figure, both find room for their quantity and both write, and the line ends up packed beyond
     * what was ordered. The unique constraint on package_item does not catch that: the two rows land
     * in different packages.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT oi FROM OrderItem oi WHERE oi.id = :id")
    Optional<OrderItem> findForUpdateById(@Param("id") Long id);

    /**
     * Order lines in one fulfillment status, across all orders, as a projection - one query for the
     * page, nothing lazy in the result. Sortable by the columns of OrderItem itself (updatedAt,
     * quantity, ...); a sort over a joined column such as the product name is not resolvable on a
     * projection query.
     * <p>
     * The reservation comes in through an entity join with ON: OrderItem has no reference to its
     * reservation, the relation only points the other way. A LEFT join, because a line need not hold
     * one - WAITING has none yet, and a released reservation is deleted. reservationId is null then.
     * Released rows being deleted also means any reservation found is the line's current one, ACTIVE
     * or CONSUMED.
     * <p>
     * The count query repeats the inner joins on purpose - they can drop rows, so a plain count over
     * order_items would report a larger total than the page query can deliver. The left join is left
     * out of it: it can neither drop a line nor, with at most one reservation per line
     * (uk_reservation_order_item), duplicate one.
     */
    @Query(value = """
            select new com.supplychainmanagement.dto.order.OrderItemListDto(
                oi.id, r.id, o.orderNo, p.articleNo, p.name, oi.quantity, oi.fulfillmentStatus, oi.updatedAt)
            from OrderItem oi
              join oi.order o
              join oi.product p
              left join Reservation r on r.orderItem = oi
            where oi.fulfillmentStatus = :status
            """,
            countQuery = """
                    select count(oi)
                    from OrderItem oi
                      join oi.order o
                      join oi.product p
                    where oi.fulfillmentStatus = :status
                    """)
    Page<OrderItemListDto> findAllByFulfillmentStatus(@Param("status") FulfillmentStatus status, Pageable pageable);
}
