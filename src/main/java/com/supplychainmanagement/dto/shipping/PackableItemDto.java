package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.model.enums.FulfillmentStatus;

import java.time.LocalDateTime;

/**
 * One order line that has been picked and can still be packed - the stage package items are made
 * from.
 * <p>
 * Filled by a constructor projection in {@code OrderItemRepository.findPackable}, like
 * {@code OrderItemListDto} and {@code PickingOrderDto}: one query per page, nothing lazy left for
 * the response writer.
 * <p>
 * What the plain order-item list does not say, and a package item cannot be made without: how much
 * of the line is <em>already packed</em> (every {@code package_item} of it, in a package or loose)
 * and so how much <em>remains</em> - {@code POST /packing} refuses more than that with a 400, it
 * never clips. The picked goods are the line's consumed reservation: its id, where it was taken from
 * and when.
 */
public record PackableItemDto(
        /** The id {@code PackItem.orderItemId} takes. */
        Long orderItemId,
        /** The consumed reservation behind the pick; null for a row that predates the link. */
        Long reservationId,
        Long orderNo,
        Long articleNo,
        String productName,
        /** Ordered - the whole line. */
        Integer quantity,
        /** Already in package items, in a package or loose. */
        Long packed,
        /** Still to pack: {@code quantity - packed}, the most a {@code PackItem} may ask for. */
        Long remaining,
        /** {@code PICKED} (nothing packed yet) or {@code PACKING} (some of it). */
        FulfillmentStatus fulfillmentStatus,
        /** The storehouse the goods were picked from; null with the reservation. */
        Long storehouseId,
        String storehouseName,
        /** When the pick consumed the reservation; null for rows consumed before the column existed. */
        LocalDateTime pickedAt
) {

    /**
     * The shape of the projection: {@code remaining} is not selected but worked out here, so the
     * query does not have to repeat its sub-select for it.
     */
    public PackableItemDto(Long orderItemId, Long reservationId, Long orderNo, Long articleNo,
                           String productName, Integer quantity, Long packed,
                           FulfillmentStatus fulfillmentStatus, Long storehouseId, String storehouseName,
                           LocalDateTime pickedAt) {
        this(orderItemId, reservationId, orderNo, articleNo, productName, quantity, packed,
                quantity - packed, fulfillmentStatus, storehouseId, storehouseName, pickedAt);
    }
}
