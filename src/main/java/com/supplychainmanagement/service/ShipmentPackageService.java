package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.shipping.CreateShipmentRequest;
import com.supplychainmanagement.dto.shipping.ShipmentListDto;
import com.supplychainmanagement.dto.shipping.ShipmentPackageIdsRequest;
import com.supplychainmanagement.dto.shipping.ShipmentResponse;
import com.supplychainmanagement.dto.shipping.UpdateShipmentRequest;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Shipments: packed packages grouped for one customer and handed to a carrier. Built like a
 * package's contents in {@link PackingService} - packages move in and out of a shipment and are never
 * deleted with it. Every answer is a DTO mapped inside the transaction.
 */
public interface ShipmentPackageService {

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

    /** One shipment with its packages and their contents. */
    ShipmentResponse findShipment(Long shipmentId);
}
