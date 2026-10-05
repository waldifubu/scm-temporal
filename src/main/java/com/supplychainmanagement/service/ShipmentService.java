package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.shipping.CancelShipmentRequest;
import com.supplychainmanagement.dto.shipping.CreateShipmentRequest;
import com.supplychainmanagement.dto.shipping.ShipmentListDto;
import com.supplychainmanagement.dto.shipping.ShipmentPackageIdsRequest;
import com.supplychainmanagement.dto.shipping.ShipmentPackageListDto;
import com.supplychainmanagement.dto.shipping.ShipmentResponse;
import com.supplychainmanagement.dto.shipping.UpdateShipmentRequest;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Shipments: packed packages grouped for one customer and handed to a carrier. Built like a
 * package's contents in {@link PackingService} - packages move in and out of a shipment and are never
 * deleted with it. Every answer is a DTO mapped inside the transaction.
 * <p>
 * Planning only, the LOGISTICS side: what the carrier reports back - accepted, on the road,
 * delivered - is {@link DeliveryService}.
 */
public interface ShipmentService {

    /** Creates a shipment for the customer with the given packages - never an empty one. */
    ShipmentResponse createShipment(CreateShipmentRequest request);

    /** Puts further packages of the same customer into the shipment. */
    ShipmentResponse addShipmentPackages(Long shipmentId, ShipmentPackageIdsRequest request);

    /** Makes the shipment hold exactly the given packages; those left out are free again. */
    ShipmentResponse replaceShipmentPackages(Long shipmentId, ShipmentPackageIdsRequest request);

    /** Takes one package out of the shipment - never the last one. */
    ShipmentResponse removeShipmentPackage(Long shipmentId, Long shipmentPackageId);

    /** Replaces the shipment's own data: address, method, requested delivery date. */
    ShipmentResponse updateShipmentData(Long shipmentId, UpdateShipmentRequest request);

    /** One page of shipments, all of them or those in the given status. */
    Page<ShipmentListDto> findShipments(ShipmentStatus status, Pageable pageable);

    /**
     * The packages the planning side may still ship: PACKED and in no shipment yet - exactly the set
     * {@link #createShipment} and {@link #addShipmentPackages} accept.
     * <p>
     * A view of its own rather than the warehouse's package list: everything under /packages is
     * packing work (ADMIN, WAREHOUSE), everything under /shipments is planning (ADMIN, LOGISTICS),
     * and the two do not overlap. Without this, LOGISTICS had to go through
     * {@code GET /shipment-packages?status=PACKED} to find anything to ship - the only way there was,
     * which is why that list was LOGISTICS' as well and the split was not a split.
     * <p>
     * It says more than the filtered package list did: that one also showed packages already
     * travelling in a shipment, so every id taken from it was a guess that {@code POST /shipments}
     * answered with a 409.
     */
    Page<ShipmentPackageListDto> findShippablePackages(Pageable pageable);

    /** One shipment with its packages and their contents. */
    ShipmentResponse findShipment(Long shipmentId);

    /** Hands the shipment to a distributor - the user behind the id has to be a Distributor. */
    ShipmentResponse assignDistributor(Long shipmentId, Long distributorId);

    /**
     * Calls the shipment off while it is still in the house: its packages are free again, the order
     * lines and orders it had moved on go back a step, and the reason is kept on the shipment.
     */
    ShipmentResponse cancelShipment(Long shipmentId, CancelShipmentRequest request, Long userId);

    /**
     * Reports the shipment ready: every package has to be packed and an address to be given. It then
     * takes the order lines it carries to READY_FOR_DISPATCH and the orders behind them with it -
     * the warehouse is done with them, which is what that status says.
     */
    ShipmentResponse checkShipmentReady(Long shipmentId, Long userId);
}
