package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.reservation.ReservationSummary;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.service.FulfillmentService;
import com.supplychainmanagement.service.InventoryService;
import com.supplychainmanagement.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}"})
public class InventoryController {

    private final FulfillmentService fulfillmentService;
    private final OrderService orderService;

    /**
     * Answers with the reservations this call created - never with the ones an earlier call already
     * made, so a repeated call yields an empty array. Which of the two reasons for that empty array
     * applies is carried by the status code: 201 something was reserved, 200 the order is fully
     * reserved and there was nothing left to do, 202 lines are still outstanding and the call is
     * worth repeating once stock arrives.
     */
    @PostMapping(path = "/orders/{orderId}/reserve", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public ResponseEntity<List<Reservation>> reserve(@PathVariable Long orderId,
                                                     @AuthenticationPrincipal User authUser) {
        var order = orderService.findByOrderNo(orderId);

        ReservationSummary summary = fulfillmentService.reserveItems(order, authUser.getUsername());

        HttpStatus status = switch (summary.outcome()) {
            case CREATED -> HttpStatus.CREATED;
            case COMPLETE -> HttpStatus.OK;
            case PENDING -> HttpStatus.ACCEPTED;
        };
        return ResponseEntity.status(status).body(summary.created());
    }

    @PostMapping(path = "/orders/{orderId}/release", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public ResponseEntity<Void> release(@PathVariable Long orderId) {
        var order = orderService.findByOrderNo(orderId);

        fulfillmentService.releaseItems(order);

        return ResponseEntity.ok().build();
    }


    //@TODO: Never used atm.
    /*
    @PostMapping(path = "/orders/{orderId}/consume", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ResponseEntity<Void> consume(@PathVariable String orderId, @Valid @RequestBody List<ReserveItem> items) {

        inventoryService.consumeWithRetry(orderId, items);

        return ResponseEntity.ok().build();
    }
    */
}
