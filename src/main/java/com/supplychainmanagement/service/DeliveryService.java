package com.supplychainmanagement.service;

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

    /**
     * DISPATCH_REQUESTED to ACCEPTED - the shipment has to have been handed over to this distributor
     * first, which is what sets that status. Any other status is a 409, a shipment assigned to
     * somebody else a 403 (ADMIN excepted).
     */
    DeliveryResponse acceptShipment(Long shipmentId, Long userId);

    /**
     * ACCEPTED to IN_TRANSIT: stamps {@code shippedAt}, takes the packages from PACKED to DISPATCHED
     * and the orders to IN_TRANSIT.
     */
    DeliveryResponse shipmentInTransit(Long shipmentId, Long userId);

    /** IN_TRANSIT to DELIVERED: stamps {@code deliveredAt} and takes the orders to DELIVERED. */
    DeliveryResponse shipmentDelivered(Long shipmentId, Long userId);

    DeliveryResponse assignTrackNumber(Long shipmentId, String trackingNumber, Long userId);

}
