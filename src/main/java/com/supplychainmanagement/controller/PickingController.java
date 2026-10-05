package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.service.OrderHandlingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Picking - the reservations waiting to be picked and picking them, one reservation or a whole
 * order. The lines picked here are what PackingController's /order-items lists for packing.
 * <p>
 * Errors go to {@code GlobalExceptionHandler} like everywhere else, so an {@code APIException}
 * answers {@code ErrorDetails} at its own status. These two endpoints used to catch it themselves
 * and answer a bare {@code {"message": ...}} map, which meant a client had to know two error shapes
 * and tell them apart by the path. Nothing was lost in the change: {@code ErrorDetails} carries the
 * same {@code message}, plus the timestamp, the path and an error code. With the catch gone the
 * return type is the DTO itself rather than {@code ResponseEntity<?>} - the wildcard only existed to
 * let a map share the signature with it.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}"})
public class PickingController {

    private final OrderHandlingService orderHandlingService;

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
    public PickingOrderDto pickingByReservationId(@PathVariable Long reservationId) {
        return orderHandlingService.pickingReservationById(reservationId);
    }

    /**
     * Pick all reservations associated with a specific order number. This allows warehouse staff to process all items related to a single order in one operation.
     *
     * @param orderNo The order number for which to pick all reservations.
     * @return ResponseEntity containing a list of PickingOrderDto if successful, or an error message if the reservations cannot be picked.
     */
    @PostMapping(path = "/picking/order/{orderNo}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public List<PickingOrderDto> pickingByOrderNo(@PathVariable String orderNo) {
        List<PickingOrderDto> pickingOrders = orderHandlingService.pickingReservationByOrderNo(orderNo);

        if (pickingOrders.isEmpty()) {
            throw new ResourceNotFoundException("Picking", "Order", Long.parseLong(orderNo));
        }

        return pickingOrders;
    }
}
