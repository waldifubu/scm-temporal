package com.supplychainmanagement.dto.order;

import com.supplychainmanagement.model.enums.ShipmentStatus;

/**
 * How much of one order line sits in shipments of one status - the raw material the order status is
 * computed from.
 * <p>
 * One row per line <em>and</em> shipment status on purpose: a line may be packed in several runs and
 * travel in several shipments, so 5 of it can be delivered while the other 5 are still in transit.
 * Which statuses count towards which order status is decided by the caller, not by the query.
 *
 * @param orderId        the order the line belongs to - the query answers for a batch of orders
 * @param orderItemId    the line
 * @param shipmentStatus the status the carrying shipments are in
 * @param quantity       the packed quantity across them
 */
public record ShippedQuantity(Long orderId, Long orderItemId, ShipmentStatus shipmentStatus, Long quantity) {
}
