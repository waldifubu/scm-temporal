package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.shipping.CancelShipmentRequest;
import com.supplychainmanagement.dto.shipping.DeliveryResponse;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    /**
     * The shipments assigned to one distributor, optionally narrowed to a status - their work list.
     * Two queries like every other shipment list: the page, then the packages, because a collection
     * fetch in a paged query is paged in memory.
     */
    Page<DeliveryResponse> findShipmentsForDistributor(Long distributorId, ShipmentStatus status, Pageable pageable);

    /** READY or DISPATCH_REQUESTED to ACCEPTED; the orders of the shipment go to READY_FOR_DISPATCH. */
    DeliveryResponse acceptShipment(Long shipmentId, Long userId);

    /**
     * ACCEPTED to IN_TRANSIT: stamps {@code shippedAt}, takes the packages from PACKED to DISPATCHED
     * and the orders to IN_TRANSIT.
     */
    DeliveryResponse shipmentInTransit(Long shipmentId, Long userId);

    /** IN_TRANSIT to DELIVERED: stamps {@code deliveredAt} and takes the orders to DELIVERED. */
    DeliveryResponse shipmentDelivered(Long shipmentId, Long userId);

    DeliveryResponse assignTrackNumber(Long shipmentId, String trackingNumber);

    /**
     * The carrier hands the shipment back: possible up to ACCEPTED, so after taking it on but before
     * it rolls - from IN_TRANSIT it would be a return, which the process does not model (409).
     * <p>
     * Everything the shipment had set in motion is wound back: its packages are loose again and stay
     * PACKED, the order lines go from READY_FOR_DISPATCH to PACKED and the orders from
     * READY_FOR_DISPATCH to IN_FULFILLMENT. That winding back is the shipment's own business, so it
     * is {@link ShipmentService#cancelShipment} doing it - one implementation for both callers, the
     * planning side and this one. Only the answer differs: the carrier is not shown the contents.
     */
    DeliveryResponse cancelShipment(Long shipmentId, CancelShipmentRequest request, Long userId);
}
