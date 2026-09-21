package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.shipping.CreateShipmentRequest;
import com.supplychainmanagement.dto.shipping.PackageItemResponse;
import com.supplychainmanagement.dto.shipping.ShipmentListDto;
import com.supplychainmanagement.dto.shipping.ShipmentPackageIdsRequest;
import com.supplychainmanagement.dto.shipping.ShipmentPackageListDto;
import com.supplychainmanagement.dto.shipping.ShipmentResponse;
import com.supplychainmanagement.dto.shipping.UpdateShipmentRequest;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.service.ShipmentPackageService;
import com.supplychainmanagement.service.ShippingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}"})
public class ShipmentController {

    private final ShippingService shippingService;
    private final ShipmentPackageService shipmentPackageService;

    /**
     * All package items - loose ones from POST /packing as well as those in a package, which is what
     * {@code shipmentPackageId} tells apart. Paged like the other lists; {@code sort} takes the fields
     * of the package item itself (id, created, quantity, runNo, ...).
     */
    @GetMapping(path = "/packages", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE','LOGISTICS')")
    public PageResponse<PackageItemResponse> getPackageItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(shippingService.findPackageItems(pageRequest(page, size, sort, order)));
    }

    /**
     * Shipment packages in one status - OPEN by default, the ones still being filled. Paged like the
     * order list in {@code OrderController.list}, down to the parameter names. {@code sort} takes the
     * fields of the package itself (id, packageNumber, createdAt, ...).
     */
    @GetMapping(path = "/shipment-packages", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE','LOGISTICS')")
    public PageResponse<ShipmentPackageListDto> getShipmentPackages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "OPEN") ShipmentPackageStatus status,
            @RequestParam(defaultValue = "") String packageNumber,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(shippingService.findShipmentPackages(status, packageNumber, pageRequest(page, size, sort, order)));
    }

    /**
     * The package items not in any package yet - loose ones from POST /packing, the candidates for
     * POST /packing/shipment/{id}/items. Same paging and sort fields as /packages.
     */
    @GetMapping(path = "/lonely-packages", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE','LOGISTICS')")
    public PageResponse<PackageItemResponse> getLonelyPackageItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(shippingService.findLoosePackageItems(pageRequest(page, size, sort, order)));
    }

    /** One package item, in the same shape as the /packages list. Unknown id: 404. */
    @GetMapping(path = "/packages/{packageItemId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE','LOGISTICS')")
    public PackageItemResponse getPackageItem(@PathVariable Long packageItemId) {
        return shippingService.findPackageItem(packageItemId);
    }

    /** One package with its items, in the same shape as the /shipment-packages list. Unknown id: 404. */
    @GetMapping(path = "/shipment-packages/{shipmentPackageId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE','LOGISTICS')")
    public ShipmentPackageListDto getShipmentPackage(@PathVariable Long shipmentPackageId) {
        return shippingService.findShipmentPackage(shipmentPackageId);
    }

    // ------------------------------------------------------------------ shipments
    // Errors go through GlobalExceptionHandler: 404 for an unknown shipment, package or customer,
    // 400 for a request that breaks the rules, 409 for a state conflict.

    /**
     * Creates a shipment for one customer with at least one of their PACKED packages - see
     * {@link CreateShipmentRequest}.
     */
    @PostMapping(path = "/shipments", version = "1.0")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE','LOGISTICS')")
    public ShipmentResponse createShipment(@Valid @RequestBody CreateShipmentRequest request) {
        return shipmentPackageService.createShipment(request);
    }

    /** Puts further packages of the shipment's customer into it - the ids as a bare array or wrapped. */
    @PostMapping(path = "/shipments/{shipmentId}/packages", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE','LOGISTICS')")
    public ShipmentResponse addShipmentPackages(@PathVariable Long shipmentId,
                                                @Valid @RequestBody ShipmentPackageIdsRequest request) {
        return shipmentPackageService.addShipmentPackages(shipmentId, request);
    }

    /** Makes the shipment hold exactly the given packages; an empty list is refused. */
    @PutMapping(path = "/shipments/{shipmentId}/packages", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE','LOGISTICS')")
    public ShipmentResponse replaceShipmentPackages(@PathVariable Long shipmentId,
                                                    @Valid @RequestBody ShipmentPackageIdsRequest request) {
        return shipmentPackageService.replaceShipmentPackages(shipmentId, request);
    }

    /** Takes one package out of the shipment; it is free again. Never the last one. */
    @DeleteMapping(path = "/shipments/{shipmentId}/packages/{shipmentPackageId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE','LOGISTICS')")
    public ShipmentResponse removeShipmentPackage(@PathVariable Long shipmentId, @PathVariable Long shipmentPackageId) {
        return shipmentPackageService.removeShipmentPackage(shipmentId, shipmentPackageId);
    }

    /** The shipment's own data: address, method, requested delivery date. */
    @PutMapping(path = "/shipments/{shipmentId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE','LOGISTICS')")
    public ShipmentResponse updateShipmentData(@PathVariable Long shipmentId,
                                               @Valid @RequestBody UpdateShipmentRequest request) {
        return shipmentPackageService.updateShipmentData(shipmentId, request);
    }

    /**
     * Shipments, all of them or those in one status. Paged like the other lists; {@code sort} takes
     * the fields of the shipment itself (id, createdAt, requestedDeliveryDate, ...).
     */
    @GetMapping(path = "/shipments", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE','LOGISTICS')")
    public PageResponse<ShipmentListDto> getShipments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(shipmentPackageService.findShipments(status, pageRequest(page, size, sort, order)));
    }

    /** One shipment with its packages and their contents. Unknown id: 404. */
    @GetMapping(path = "/shipments/{shipmentId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE','LOGISTICS')")
    public ShipmentResponse getShipment(@PathVariable Long shipmentId) {
        return shipmentPackageService.findShipment(shipmentId);
    }

    private static Pageable pageRequest(int page, int size, String sort, String order) {
        Sort.Direction direction = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageRequest.of(page, size, Sort.by(direction, sort));
    }
    



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
