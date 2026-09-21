package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.shipping.*;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.service.ShipmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Shipments - everything under /shipments: creating them from PACKED packages of one customer,
 * changing which packages they hold, and reading them.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}"})
public class ShipmentController {

    private final ShipmentService shipmentService;

    private static Pageable pageRequest(int page, int size, String sort, String order) {
        Sort.Direction direction = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageRequest.of(page, size, Sort.by(direction, sort));
    }

    /**
     * Creates a shipment for one customer with at least one of their PACKED packages - see
     * {@link CreateShipmentRequest}.
     */
    @PostMapping(path = "/shipments", version = "1.0")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('ADMIN','LOGISTICS')")
    public ShipmentResponse createShipment(@Valid @RequestBody CreateShipmentRequest request) {
        return shipmentService.createShipment(request);
    }

    /**
     * Puts further packages of the shipment's customer into it - the ids as a bare array or wrapped.
     */
    @PostMapping(path = "/shipments/{shipmentId}/packages", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','LOGISTICS')")
    public ShipmentResponse addShipmentPackages(@PathVariable Long shipmentId,
                                                @Valid @RequestBody ShipmentPackageIdsRequest request) {
        return shipmentService.addShipmentPackages(shipmentId, request);
    }

    /**
     * Makes the shipment hold exactly the given packages; an empty list is refused.
     */
    @PutMapping(path = "/shipments/{shipmentId}/packages", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','LOGISTICS')")
    public ShipmentResponse replaceShipmentPackages(@PathVariable Long shipmentId,
                                                    @Valid @RequestBody ShipmentPackageIdsRequest request) {
        return shipmentService.replaceShipmentPackages(shipmentId, request);
    }

    /**
     * Takes one package out of the shipment; it is free again. Never the last one.
     */
    @DeleteMapping(path = "/shipments/{shipmentId}/packages/{shipmentPackageId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','LOGISTICS')")
    public ShipmentResponse removeShipmentPackage(@PathVariable Long shipmentId, @PathVariable Long shipmentPackageId) {
        return shipmentService.removeShipmentPackage(shipmentId, shipmentPackageId);
    }

    /**
     * The shipment's own data: address, method, requested delivery date.
     */
    @PutMapping(path = "/shipments/{shipmentId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','LOGISTICS')")
    public ShipmentResponse updateShipmentData(@PathVariable Long shipmentId,
                                               @Valid @RequestBody UpdateShipmentRequest request) {
        return shipmentService.updateShipmentData(shipmentId, request);
    }

    /**
     * Shipments, all of them or those in one status. Paged like the other lists; {@code sort} takes
     * the fields of the shipment itself (id, createdAt, requestedDeliveryDate, ...).
     */
    @GetMapping(path = "/shipments", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','LOGISTICS')")
    public PageResponse<ShipmentListDto> getShipments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(shipmentService.findShipments(status, pageRequest(page, size, sort, order)));
    }

    /**
     * One shipment with its packages and their contents. Unknown id: 404.
     */
    @GetMapping(path = "/shipments/{shipmentId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','LOGISTICS')")
    public ShipmentResponse getShipment(@PathVariable Long shipmentId) {
        return shipmentService.findShipment(shipmentId);
    }

    /**
     * Assigns a distributor to the shipment. This is the step where the shipment is handed over to the distributor for delivery. The distributor must be valid and capable of handling the shipment.
     */
    @PutMapping(path = "/shipments/{shipmentId}/distributor/{distributorId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','LOGISTICS')")
    public ShipmentResponse assignDistributor(@PathVariable Long shipmentId, @PathVariable Long distributorId) {
        return shipmentService.assignDistributor(shipmentId, distributorId);
    }
    

/*
    // Packing (/packing/**) lives in PackingController


    @NoCheck
    @PostMapping(path = "/dispatch/{reservationId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ResponseEntity<?> readyForDispatch(@PathVariable Long reservationId) {
        PickingOrderDto packingOrderItem = null;
        try {
            packingOrderItem = orderHandlingService.readyForDispatch(reservationId);
        } catch (APIException e) {
            Map<String, String> response = new HashMap<>();
            response.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(response);
        }

        return ResponseEntity.ok(packingOrderItem);
    }


    /*
alle Packages PACKED
        ↓
Shipment READY
        ↓
Distributor übernimmt
        ↓
IN_TRANSIT



     !!! Auch wenn ein Shipment mehrere Orders enthalten darf, würde ich ein Package niemals mit unterschiedlichen Orders mischen. !!!
     Ein Package sollte weiterhin nur eine Order enthalten

### Warehouse

Order
  ↓
Picking
  ↓
PackageItem
  ↓
ShipmentPackage
  ↓
PACKED


### Logistics

PACKED ShipmentPackages
  ↓
Shipment
  ↓
Distributor
  ↓
Dispatch



ORDER
  │
  ▼
RESERVATION
  │
  ▼
PICKING
  │
  ▼
PACKAGE ITEMS
  │
  ▼
SHIPMENT PACKAGE
  │
  │  Warehouse
  │
  ▼
PACKED
  │
  │  Logistics
  ▼
SHIPMENT
  │
  ▼
DISTRIBUTOR
  │
  ▼
IN_TRANSIT
  │
  ▼
DELIVERED
     */

    /*
    /receipts
    /picklists
    /shipments
    /stock
    /locations
     */

//    Filter: Order = CONVEYABLE

//    GET /api/v1/shipments?status=READY
/*
    {
        "content": [
        {
            "shipmentId": "SHIP-20001",
                "orderId": 8,
                "status": "READY",
                "storehouseId": 1,
                "destination": {
            "name": "Muster GmbH",
                    "street": "Hauptstraße 10",
                    "postalCode": "50667",
                    "city": "Köln",
                    "country": "DE"
        },
            "packageCount": 2,
                "weight": 12.5
        }
  ],
        "page": 0,
            "size": 20,
            "totalElements": 1,
            "totalPages": 1
    }
    */


//    POST /api/v1/shipments/SHIP-20001/dispatch
}
