package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.shipping.DeliveryResponse;

/**
 * The carrier's side of a shipment: taking it on, reporting it on the road and reporting it
 * delivered. Driven by the DISTRIBUTOR, while planning a shipment - creating it, filling it,
 * reporting it ready, calling it off - belongs to LOGISTICS and stays in {@link ShipmentService}.
 * <p>
 * Deliberately not called LogisticsService: {@code RoleEnum.LOGISTICS} is the inside role that plans
 * shipments, not the one reporting here. Tracking and returns will land here.
 * <p>
 * Answers with {@link DeliveryResponse}, not with the shipment including its package contents - the
 * carrier is not shown what is inside.
 */
public interface DeliveryService {

    /** READY or DISPATCH_REQUESTED to ACCEPTED; the orders of the shipment go to READY_FOR_DISPATCH. */
    DeliveryResponse acceptShipment(Long shipmentId, Long userId);

    /**
     * ACCEPTED to IN_TRANSIT: stamps {@code shippedAt}, takes the packages from PACKED to DISPATCHED
     * and the orders to IN_TRANSIT.
     */
    DeliveryResponse shipmentInTransit(Long shipmentId, Long userId);

    /** IN_TRANSIT to DELIVERED: stamps {@code deliveredAt} and takes the orders to DELIVERED. */
    DeliveryResponse shipmentDelivered(Long shipmentId, Long userId);
}
