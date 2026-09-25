package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.shipping.CancelShipmentRequest;
import com.supplychainmanagement.dto.shipping.DeliveryResponse;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.service.DeliveryService;
import com.supplychainmanagement.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.*;

/**
 * The carrier's side of a shipment - what the DISTRIBUTOR does with one: see the shipments assigned
 * to them, take one on, report it on the road and report it delivered.
 * <p>
 * The paths stay under {@code /shipments} because that is the resource; what separates this from
 * {@link ShipmentController} is the role and the direction. Planning a shipment - creating it,
 * filling it, reporting it ready, assigning a distributor, calling it off - is the inside job of
 * LOGISTICS and stays there. Assigning a distributor belongs to that side too: it is the house
 * choosing a carrier, not the carrier answering.
 * <p>
 * Every answer here is a {@link DeliveryResponse}, never a {@code ShipmentResponse}: the carrier is
 * told where the shipment goes and how many packages weighing how much, not what is inside them.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}"})
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final UserService userService;

    private static Pageable pageRequest(int page, int size, String sort, String order) {
        Sort.Direction direction = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageRequest.of(page, size, Sort.by(direction, sort));
    }

    /**
     * The shipments assigned to the distributor making the call - their whole work list, optionally
     * narrowed to one status. Paged like the other lists; {@code sort} takes the fields of the
     * shipment itself (id, createdAt, requestedDeliveryDate, ...).
     * <p>
     * No distributor id in the path: a carrier is shown their own shipments and nobody else's, so it
     * comes from the authenticated user. An ADMIN calling this is not a distributor and gets an empty
     * page - the full list is {@code GET /shipments}.
     */
    @GetMapping(path = "/shipments/distributor", version = "1.0")
    @PreAuthorize("hasAnyAuthority('DISTRIBUTOR')")
    public PageResponse<DeliveryResponse> getMyShipments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(defaultValue = "ASC") String order,
            @AuthenticationPrincipal User authUser) {
        return PageResponse.of(deliveryService.findShipmentsForDistributor(
                userService.getAuthenticatedUserId(authUser), status, pageRequest(page, size, sort, order)));
    }

    /**
     * The distributor takes the shipment on: READY or DISPATCH_REQUESTED to ACCEPTED, every order it
     * carries to READY_FOR_DISPATCH. Any other status is a 409.
     */
    @PostMapping(path = "/shipments/{shipmentId}/accept", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','DISTRIBUTOR')")
    public DeliveryResponse acceptShipment(@PathVariable Long shipmentId,
                                           @AuthenticationPrincipal User authUser) {
        return deliveryService.acceptShipment(shipmentId, userService.getAuthenticatedUserId(authUser));
    }

    /** The shipment is on its way: ACCEPTED to IN_TRANSIT, its packages to DISPATCHED, its orders to IN_TRANSIT. */
    @PostMapping(path = "/shipments/{shipmentId}/intransit", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','DISTRIBUTOR')")
    public DeliveryResponse shipmentInTransit(@PathVariable Long shipmentId,
                                              @AuthenticationPrincipal User authUser) {
        return deliveryService.shipmentInTransit(shipmentId, userService.getAuthenticatedUserId(authUser));
    }

    /** The carrier's own reference for the shipment; not once it is IN_TRANSIT or DELIVERED. */
    @PostMapping(path = "/shipments/{shipmentId}/tracknumber", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','DISTRIBUTOR')")
    public DeliveryResponse trackNumber(@PathVariable Long shipmentId,
                                              @RequestBody String trackingNumber,
                                              @AuthenticationPrincipal User authUser) {
        return deliveryService.assignTrackNumber(shipmentId, trackingNumber);
    }

    /** The shipment has arrived: IN_TRANSIT to DELIVERED, its orders to DELIVERED. */
    @PostMapping(path = "/shipments/{shipmentId}/delivered", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','DISTRIBUTOR')")
    public DeliveryResponse shipmentDelivered(@PathVariable Long shipmentId,
                                              @AuthenticationPrincipal User authUser) {
        return deliveryService.shipmentDelivered(shipmentId, userService.getAuthenticatedUserId(authUser));
    }

    /**
     * The carrier hands the shipment back - up to ACCEPTED, so after taking it on but before it
     * rolls; from IN_TRANSIT on it would be a return and is a 409. The reason is required and kept on
     * the shipment. Its packages are loose again and stay PACKED, the order lines go back to PACKED
     * and the orders to IN_FULFILLMENT.
     * <p>
     * The planning side calls the same thing off with {@code POST /shipments/{id}/cancel} - same path,
     * different verb and different role, and both end in the same service method.
     */
    @PutMapping(path = "/shipments/{shipmentId}/cancel", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','DISTRIBUTOR')")
    public DeliveryResponse cancelShipment(@PathVariable Long shipmentId,
                                           @Valid @RequestBody CancelShipmentRequest request,
                                           @AuthenticationPrincipal User authUser) {
        return deliveryService.cancelShipment(shipmentId, request, userService.getAuthenticatedUserId(authUser));
    }
}
