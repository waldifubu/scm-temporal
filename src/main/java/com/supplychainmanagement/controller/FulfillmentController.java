package com.supplychainmanagement.controller;

import com.supplychainmanagement.annotation.NoCheck;
import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.order.OrderItemListDto;
import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.dto.shipping.CreatePackageItemsRequest;
import com.supplychainmanagement.dto.shipping.CreatePackageRequest;
import com.supplychainmanagement.dto.shipping.PackageItemResponse;
import com.supplychainmanagement.dto.shipping.ShipmentPackageResponse;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.service.OrderHandlingService;
import com.supplychainmanagement.service.PackingService;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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
     * Order lines in one fulfillment status across all orders - PICKED by default, the lines waiting
     * to be packed. Paged like the order list in {@code OrderController.list}, down to the parameter
     * names. Sorted by {@code updatedAt} by default, so the line that reached its status first comes
     * first. {@code sort} accepts the columns of the order line itself; a joined one such as the
     * product name fails, see {@code OrderItemRepository.findAllByFulfillmentStatus}.
     */
    @GetMapping(path = "/order-items", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public PageResponse<OrderItemListDto> getOrderItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "updatedAt") String sort,
            @RequestParam(defaultValue = "PICKED") FulfillmentStatus status,
            @RequestParam(defaultValue = "ASC") String order) {
        Sort.Direction dir = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sort));

        return PageResponse.of(orderHandlingService.getOrderItems(status, pageable));
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

        if (pickingOrders.isEmpty()) {
            throw new ResourceNotFoundException("Picking", "Order", Long.parseLong(orderNo));
        }

        return ResponseEntity.ok(pickingOrders);
    }


    /**
     * Packs the given lines into a new package, so items are required here - hence the WithItems
     * group next to Default. See {@link CreatePackageRequest#items()} for the use cases without.
     */
    @PostMapping(path = "/packing/{orderNo}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ResponseEntity<?> createShipmentPackage(
            @PathVariable Long orderNo,
            @Validated({Default.class, CreatePackageRequest.WithItems.class})
            @RequestBody CreatePackageRequest createPackageRequest) {
        ShipmentPackage shipmentPackage;
        try {
            shipmentPackage = packingService.createShipmentPackage(orderNo, createPackageRequest);
        } catch (APIException e) {
            Map<String, String> response = new HashMap<>();
            response.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(response);
        }

        return ResponseEntity.ok(ShipmentPackageResponse.from(shipmentPackage));
    }

    /**
     * Packs order lines without a package. Everything this call creates shares one runNo, carried by
     * every item in the response.
     * <p>
     * Answered in the paged shape of the list endpoints - one page holding exactly the items created
     * here - and as DTOs: PackageItem reaches Order through its order item, and Order leads back to
     * its items, a cycle Jackson cannot serialize.
     */
    @PostMapping(path = "/packing", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ResponseEntity<?> createPackageItems(
            @Valid @RequestBody CreatePackageItemsRequest createPackageItemsRequest) {

        List<PackageItem> packageItems;
        try {
            packageItems = packingService.createPackageItems(createPackageItemsRequest);
        } catch (APIException e) {
            Map<String, String> response = new HashMap<>();
            response.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(response);
        }

        List<PackageItemResponse> responses = packageItems.stream()
                .map(PackageItemResponse::from)
                .toList();

        return ResponseEntity.ok(PageResponse.of(new PageImpl<>(responses)));
    }

    @PostMapping(path = "/package/empty", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ResponseEntity<?> createEmptyShipment(@Valid @RequestBody CreatePackageRequest createPackageRequest) {
        ShipmentPackage shipmentPackage;
        try {
            shipmentPackage = packingService.createEmptyShipment(createPackageRequest);
        } catch (APIException e) {
            Map<String, String> response = new HashMap<>();
            response.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(response);
        }

        return ResponseEntity.ok(ShipmentPackageResponse.from(shipmentPackage));
    }

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
    /receipts
    /picklists
    /shipments
    /stock
    /locations
     */
}
