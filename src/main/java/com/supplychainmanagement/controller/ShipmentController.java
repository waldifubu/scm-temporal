package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.shipping.*;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.service.ShipmentService;
import com.supplychainmanagement.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.*;

/**
 * Shipments as LOGISTICS plans them: creating them from PACKED packages of one customer, changing
 * which packages they hold, reporting them ready, assigning a distributor, calling them off, and
 * reading them.
 * <p>
 * What the carrier then does with a shipment - accept, in transit, delivered, and their own work
 * list - is {@link DeliveryController}. Assigning a distributor stays here: that is the house
 * choosing a carrier, not the carrier answering.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}"})
public class ShipmentController {

    private final ShipmentService shipmentService;
    private final UserService userService;

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
     * Checks if the shipment is ready for dispatch. A shipment is considered ready if all its packages are packed.
     * If the shipment is ready, it will be marked as READY and returned in the response.
     */
    @PutMapping(path = "/shipments/{shipmentId}/ready", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','LOGISTICS')")
    public ShipmentResponse checkShipmentIsReady(@PathVariable Long shipmentId,
                                                 @AuthenticationPrincipal User authUser) {
        return shipmentService.checkShipmentReady(shipmentId, userService.getAuthenticatedUserId(authUser));
    }

    /**
     * Assigns a distributor to the shipment. This is the step where the shipment is handed over to the distributor for delivery. The distributor must be valid and capable of handling the shipment.
     */
    @PutMapping(path = "/shipments/{shipmentId}/distributor/{distributorId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','LOGISTICS')")
    public ShipmentResponse assignDistributor(@PathVariable Long shipmentId, @PathVariable Long distributorId) {
        return shipmentService.assignDistributor(shipmentId, distributorId);
    }

    /**
     * Calls the shipment off while it is still in the house - up to ACCEPTED, later is a 409. Its
     * packages are free again, the order lines and orders it had moved on go back a step, and the
     * reason is kept on the shipment.
     */
    @PostMapping(path = "/shipments/{shipmentId}/cancel", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','LOGISTICS')")
    public ShipmentResponse cancelShipment(@PathVariable Long shipmentId,
                                           @Valid @RequestBody CancelShipmentRequest request,
                                           @AuthenticationPrincipal User authUser) {
        return shipmentService.cancelShipment(shipmentId, request, userService.getAuthenticatedUserId(authUser));
    }
}
