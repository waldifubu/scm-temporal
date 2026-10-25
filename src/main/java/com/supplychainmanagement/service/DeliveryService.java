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
    default DeliveryResponse acceptShipment(Long shipmentId, Long userId) {
        return acceptShipment(shipmentId, null, userId);
    }

    /**
     * The same, with what the carrier has to say to it: kept in the shipment's {@code distributorComment}, as
     * a rejection keeps its reason. Optional - {@code null} or blank leaves the comment as it was. Longer than
     * the column is a 400.
     */
    DeliveryResponse acceptShipment(Long shipmentId, String reason, Long userId);

    /**
     * ACCEPTED to IN_TRANSIT: stamps {@code shippedAt}, takes the packages from PACKED to DISPATCHED
     * and the orders to IN_TRANSIT.
     */
    DeliveryResponse shipmentInTransit(Long shipmentId, Long userId);

    /** IN_TRANSIT to DELIVERED: stamps {@code deliveredAt} and takes the orders to DELIVERED. */
    DeliveryResponse shipmentDelivered(Long shipmentId, Long userId);

    DeliveryResponse assignTrackingNumber(Long shipmentId, String trackingNumber, Long userId);

    /**
     * The carrier turns the shipment down: DISPATCH_REQUESTED to REJECTED, and the reason is kept in the
     * shipment's {@code distributorComment}. <strong>Nothing else happens</strong> - the packages stay in the
     * shipment, no order line and no order is touched, no order progress is recomputed. A shipment
     * assigned to somebody else is a 403 (ADMIN excepted), any status but DISPATCH_REQUESTED a 409, a blank
     * reason a 400.
     * <p>
     * DISPATCH_REQUESTED only, like {@link #acceptShipment}: it is declining what was offered, before
     * anything was promised. Once the shipment is accepted, backing out is a cancellation.
     */
    DeliveryResponse rejectShipment(Long shipmentId, String reason, Long userId);
}
