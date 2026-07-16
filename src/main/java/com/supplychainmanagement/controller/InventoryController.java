package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;


    /*
    [
  {
    "sku": "ABC-100",
    "quantity": 5
  },
  {
    "sku": "XYZ-200",
    "quantity": 2
  }
]
     */
    @PostMapping("/orders/{orderId}/reserve")
    public ResponseEntity<Void> reserve(@PathVariable String orderId, @Valid @RequestBody List<ReserveItem> items) {

        inventoryService.reserveWithRetry(orderId, items);

        return ResponseEntity.ok().build();
    }


    @PostMapping("/orders/{orderId}/release")
    public ResponseEntity<Void> release(@PathVariable String orderId, @Valid @RequestBody List<ReserveItem> items) {

        inventoryService.releaseWithRetry(orderId, items);

        return ResponseEntity.ok().build();
    }

    /*
    /receipts
    /picklists
    /shipments
    /stock
    /locations
     */
}