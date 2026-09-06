package com.supplychainmanagement.controller;

import com.supplychainmanagement.annotation.NoCheck;
import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.dto.shipping.CreatePackageRequest;
import com.supplychainmanagement.dto.shipping.ShipmentPackageResponse;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.service.OrderHandlingService;
import com.supplychainmanagement.service.PackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}"})
public class FulfillmentController {

    private final OrderHandlingService orderHandlingService;
    private final PackingService packingService;

    /**
     * Paged like the order list in {@code OrderController.list}, down to the parameter names, so the
     * two list views are driven the same way. Sorted by {@code expiresAt} by default: the
     * reservation closest to expiry is the one to pick first.
     */
    @GetMapping(path = "/picking-orders", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public PageResponse<PickingOrderDto> getPickingOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "expiresAt") String sort,
            @RequestParam(defaultValue = "ACTIVE") ReservationStatus status,
            @RequestParam(defaultValue = "ASC") String order) {
        Sort.Direction dir = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sort));

        return PageResponse.of(orderHandlingService.pickingOrders(status, pageable));
    }

    /**
     * Pick a reservation by its ID. This is the first step in the fulfillment process, where the warehouse staff retrieves the items from storage based on the reservation details.
     * Caution: Picking will nearly always work, because RESERVED was successfully. You can just pick all or nothing, not just some items.
     *
     * @param reservationId The ID of the reservation to be picked.
     * @return ResponseEntity containing the PickingOrderDto if successful, or an error message if the reservation cannot be picked.
     */
    @PostMapping(path = "/picking/{reservationId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ResponseEntity<?> pickingByReservationId(@PathVariable Long reservationId) {
        PickingOrderDto reservation;
        try {
            reservation = orderHandlingService.pickingReservationById(reservationId);
        } catch (APIException e) {
            Map<String, String> response = new HashMap<>();
            response.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(response);
        }

        return ResponseEntity.ok(reservation);
    }

    /**
     * Pick all reservations associated with a specific order number. This allows warehouse staff to process all items related to a single order in one operation.
     *
     * @param orderNo The order number for which to pick all reservations.
     * @return ResponseEntity containing a list of PickingOrderDto if successful, or an error message if the reservations cannot be picked.
     */
    @PostMapping(path = "/picking/order/{orderNo}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ResponseEntity<?> pickingByOrderNo(@PathVariable String orderNo) {
        List<PickingOrderDto> pickingOrders;
        try {
            pickingOrders = orderHandlingService.pickingReservationByOrderNo(orderNo);
        } catch (APIException e) {
            Map<String, String> response = new HashMap<>();
            response.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(response);
        }

        return ResponseEntity.ok(pickingOrders);
    }


    @PostMapping(path = "/packing/{orderNo}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ResponseEntity<?> packingStart(
            @PathVariable Long orderNo,
            @RequestBody CreatePackageRequest createPackageRequest) {

        ShipmentPackage shipmentPackage;
        try {
            shipmentPackage = packingService.createPackage(orderNo, createPackageRequest);
        } catch (APIException e) {
            Map<String, String> response = new HashMap<>();
            response.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(response);
        }

        return ResponseEntity.ok(ShipmentPackageResponse.from(shipmentPackage));
    }

    @PostMapping(path = "/packing/{reservationId}/complete", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ResponseEntity<?> completePackingByReservationId(@PathVariable Long reservationId) {
        PickingOrderDto packingOrderItem;
        try {
            packingOrderItem = packingService.packingReservationByIdComplete(reservationId);
        } catch (APIException e) {
            Map<String, String> response = new HashMap<>();
            response.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(response);
        }

        return ResponseEntity.ok(packingOrderItem);
    }

    @NoCheck
    @PostMapping(path = "/dispatch/{reservationId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ResponseEntity<?> readyForDispath(@PathVariable Long reservationId) {
        PickingOrderDto packingOrderItem = null;
        try {
            packingOrderItem = orderHandlingService.readyDispatch(reservationId);
        } catch (APIException e) {
            Map<String, String> response = new HashMap<>();
            response.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(response);
        }

        return ResponseEntity.ok(packingOrderItem);
    }
    
    /*
    /receipts
    /picklists
    /shipments
    /stock
    /locations
     */
}
